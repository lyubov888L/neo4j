/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.recordstorage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.neo4j.common.TokenNameLookup;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.impl.store.AbstractDynamicStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.PropertyType;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.PropertyBlock;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.extension.pagecache.EphemeralPageCacheExtension;
import org.neo4j.test.rule.RandomRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector.immediate;

@ExtendWith( RandomExtension.class )
@EphemeralPageCacheExtension
class PropertyDeleterTest
{
    @Inject
    private TestDirectory directory;
    @Inject
    private PageCache pageCache;
    @Inject
    private RandomRule random;

    private final PropertyTraverser traverser = new PropertyTraverser();
    private final AssertableLogProvider logProvider = new AssertableLogProvider();
    private PropertyCreator propertyCreator;
    private NeoStores neoStores;
    private PropertyDeleter deleter;
    private PropertyStore propertyStore;
    private DefaultIdGeneratorFactory idGeneratorFactory;

    private void startStore( boolean log )
    {
        Config config = Config.defaults( GraphDatabaseSettings.log_inconsistent_data_deletion, log );
        idGeneratorFactory = new DefaultIdGeneratorFactory( directory.getFileSystem(), immediate() );
        neoStores = new StoreFactory( DatabaseLayout.ofFlat( directory.homeDir() ), config, idGeneratorFactory, pageCache, directory.getFileSystem(),
                NullLogProvider.getInstance() ).openAllNeoStores( true );
        propertyStore = neoStores.getPropertyStore();
        propertyCreator = new PropertyCreator( propertyStore, new PropertyTraverser() );
        deleter = new PropertyDeleter( traverser, this.neoStores, new TokenNameLookup()
        {
            @Override
            public String labelGetName( int labelId )
            {
                return "" + labelId;
            }

            @Override
            public String relationshipTypeGetName( int relationshipTypeId )
            {
                return "" + relationshipTypeId;
            }

            @Override
            public String propertyKeyGetName( int propertyKeyId )
            {
                return "" + propertyKeyId;
            }
        }, logProvider, config );
    }

    @AfterEach
    void stopStore()
    {
        neoStores.close();
    }

    @ValueSource( booleans = {true, false} )
    @ParameterizedTest
    void shouldHandlePropertyChainDeletionOnCycle( boolean log )
    {
        // given
        startStore( log );
        NodeStore nodeStore = neoStores.getNodeStore();
        NodeRecord node = nodeStore.newRecord();
        node.setId( nodeStore.nextId() );
        List<PropertyBlock> properties = new ArrayList<>();
        for ( int i = 0; i < 20; i++ )
        {
            properties.add( encodedValue( i, random.nextValue() ) );
        }
        DirectRecordAccessSet initialChanges = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        long firstPropId = propertyCreator.createPropertyChain( node, properties.iterator(), initialChanges.getPropertyRecords() );
        node.setNextProp( firstPropId );
        initialChanges.close(); // should update all the changed records directly into the store

        // create a cycle in the property chain A -> B
        //                                      ^---v
        List<Value> valuesInTheFirstTwoRecords = new ArrayList<>();
        PropertyRecord firstPropRecord = propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        readValuesFromPropertyRecord( firstPropRecord, valuesInTheFirstTwoRecords );
        long secondPropId = firstPropRecord.getNextProp();
        PropertyRecord secondPropRecord = propertyStore.getRecord( secondPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        readValuesFromPropertyRecord( secondPropRecord, valuesInTheFirstTwoRecords );
        secondPropRecord.setNextProp( firstPropId );
        propertyStore.updateRecord( secondPropRecord );

        // when
        DirectRecordAccessSet changes = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        deleter.deletePropertyChain( node, changes.getPropertyRecords() );
        changes.close();

        // then
        assertEquals( Record.NO_NEXT_PROPERTY.longValue(), node.getNextProp() );
        assertFalse( propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.CHECK ).inUse() );
        assertFalse( propertyStore.getRecord( secondPropId, propertyStore.newRecord(), RecordLoad.CHECK ).inUse() );
        if ( log )
        {
            logProvider.formattedMessageMatcher().assertContains( "Deleted inconsistent property chain with cycle" );
            for ( Value value : valuesInTheFirstTwoRecords )
            {
                logProvider.formattedMessageMatcher().assertContains( containsString( value.toString() ) );
            }
        }
        else
        {
            logProvider.formattedMessageMatcher().assertNotContains( "Deleted inconsistent property chain with cycle" );
        }
    }

    @ValueSource( booleans = {true, false} )
    @ParameterizedTest
    void shouldHandlePropertyChainDeletionOnUnusedRecord( boolean log )
    {
        // given
        startStore( log );
        NodeStore nodeStore = neoStores.getNodeStore();
        NodeRecord node = nodeStore.newRecord();
        node.setId( nodeStore.nextId() );
        List<PropertyBlock> properties = new ArrayList<>();
        for ( int i = 0; i < 20; i++ )
        {
            properties.add( encodedValue( i, random.nextValue() ) );
        }
        DirectRecordAccessSet initialChanges = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        long firstPropId = propertyCreator.createPropertyChain( node, properties.iterator(), initialChanges.getPropertyRecords() );
        node.setNextProp( firstPropId );
        initialChanges.close(); // should update all the changed records directly into the store

        // create a cycle in the property chain A -> B
        //                                      ^---v
        List<Value> valuesInTheFirstTwoRecords = new ArrayList<>();
        PropertyRecord firstPropRecord = propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        readValuesFromPropertyRecord( firstPropRecord, valuesInTheFirstTwoRecords );
        long secondPropId = firstPropRecord.getNextProp();
        PropertyRecord secondPropRecord = propertyStore.getRecord( secondPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        readValuesFromPropertyRecord( secondPropRecord, valuesInTheFirstTwoRecords );
        long thirdPropId = secondPropRecord.getNextProp();
        PropertyRecord thirdPropRecord = propertyStore.getRecord( thirdPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        thirdPropRecord.setInUse( false );
        propertyStore.updateRecord( thirdPropRecord );

        // when
        DirectRecordAccessSet changes = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        deleter.deletePropertyChain( node, changes.getPropertyRecords() );
        changes.close();

        // then
        assertEquals( Record.NO_NEXT_PROPERTY.longValue(), node.getNextProp() );
        assertFalse( propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.CHECK ).inUse() );
        assertFalse( propertyStore.getRecord( secondPropId, propertyStore.newRecord(), RecordLoad.CHECK ).inUse() );
        if ( log )
        {
            logProvider.formattedMessageMatcher().assertContains( "Deleted inconsistent property chain with unused record" );
        }
        else
        {
            logProvider.formattedMessageMatcher().assertNotContains( "Deleted inconsistent property chain with unused record" );
        }
    }

    @ValueSource( booleans = {true, false} )
    @ParameterizedTest
    void shouldHandlePropertyChainDeletionOnDynamicRecordCycle( boolean log )
    {
        // given
        startStore( log );
        NodeStore nodeStore = neoStores.getNodeStore();
        NodeRecord node = nodeStore.newRecord();
        node.setId( nodeStore.nextId() );
        List<PropertyBlock> properties = Collections.singletonList( encodedValue( 0, random.randomValues().nextAsciiTextValue( 1000, 1000 ) ) );
        DirectRecordAccessSet initialChanges = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        long firstPropId = propertyCreator.createPropertyChain( node, properties.iterator(), initialChanges.getPropertyRecords() );
        node.setNextProp( firstPropId );
        initialChanges.close(); // should update all the changed records directly into the store

        // create a cycle in the dynamic record chain cycle
        PropertyRecord firstPropRecord = propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        PropertyBlock dynamicBlock = firstPropRecord.iterator().next();
        createCycleIn( dynamicBlock );

        // when
        DirectRecordAccessSet changes = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        deleter.deletePropertyChain( node, changes.getPropertyRecords() );
        changes.close();

        // then
        assertEquals( Record.NO_NEXT_PROPERTY.longValue(), node.getNextProp() );
        if ( log )
        {
            logProvider.formattedMessageMatcher().assertContains( "Deleted inconsistent property chain" );
        }
        else
        {
            logProvider.formattedMessageMatcher().assertNotContains( "Deleted inconsistent property chain" );
        }
    }

    @ValueSource( booleans = {true, false} )
    @ParameterizedTest
    void shouldHandlePropertyChainDeletionOnUnusedDynamicRecord( boolean log )
    {
        // given
        startStore( log );
        NodeStore nodeStore = neoStores.getNodeStore();
        NodeRecord node = nodeStore.newRecord();
        node.setId( nodeStore.nextId() );
        List<PropertyBlock> properties = Collections.singletonList( encodedValue( 0, random.randomValues().nextAsciiTextValue( 1000, 1000 ) ) );
        DirectRecordAccessSet initialChanges = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        long firstPropId = propertyCreator.createPropertyChain( node, properties.iterator(), initialChanges.getPropertyRecords() );
        node.setNextProp( firstPropId );
        initialChanges.close(); // should update all the changed records directly into the store

        PropertyRecord firstPropRecord = propertyStore.getRecord( firstPropId, propertyStore.newRecord(), RecordLoad.NORMAL );
        PropertyBlock dynamicBlock = firstPropRecord.iterator().next();
        makeSomeUnusedIn( dynamicBlock );

        // when
        DirectRecordAccessSet changes = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        deleter.deletePropertyChain( node, changes.getPropertyRecords() );
        changes.close();

        // then
        assertEquals( Record.NO_NEXT_PROPERTY.longValue(), node.getNextProp() );
        if ( log )
        {
            logProvider.formattedMessageMatcher().assertContains( "Deleted inconsistent property chain with unused record" );
        }
        else
        {
            logProvider.formattedMessageMatcher().assertNotContains( "Deleted inconsistent property chain with unused record" );
        }
    }

    @Test
    void shouldLogAsManyPropertiesAsPossibleEvenThoSomeDynamicChainsAreBroken()
    {
        // given
        startStore( true );
        NodeStore nodeStore = neoStores.getNodeStore();
        NodeRecord node = nodeStore.newRecord();
        node.setId( nodeStore.nextId() );
        List<PropertyBlock> properties = new ArrayList<>();
        // We're not tampering with our "small" values and they should therefore be readable and logged during deletion
        Value value1 = randomValueWithMaxSingleDynamicRecord();
        Value value2 = randomValueWithMaxSingleDynamicRecord();
        Value bigValue1 = random.nextAlphaNumericTextValue( 1_000, 1_000 );
        Value bigValue2 = randomLargeLongArray();
        Value value3 = randomValueWithMaxSingleDynamicRecord();
        properties.add( encodedValue( 0, value1 ) );
        properties.add( encodedValue( 1, value2 ) );
        properties.add( encodedValue( 2, bigValue1 ) );
        properties.add( encodedValue( 3, bigValue2 ) );
        properties.add( encodedValue( 4, value3 ) );
        DirectRecordAccessSet initialChanges = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        long firstPropId = propertyCreator.createPropertyChain( node, properties.iterator(), initialChanges.getPropertyRecords() );
        node.setNextProp( firstPropId );
        initialChanges.close(); // should update all the changed records directly into the store

        List<PropertyBlock> blocksWithDynamicValueRecords = getBlocksContainingDynamicValueRecords( firstPropId );
        makeSomeUnusedIn( random.among( blocksWithDynamicValueRecords ) );
        createCycleIn( random.among( blocksWithDynamicValueRecords ) );

        // when
        DirectRecordAccessSet changes = new DirectRecordAccessSet( neoStores, idGeneratorFactory );
        deleter.deletePropertyChain( node, changes.getPropertyRecords() );
        changes.close();

        // then
        assertEquals( Record.NO_NEXT_PROPERTY.longValue(), node.getNextProp() );
        logProvider.formattedMessageMatcher().assertContains( "Deleted inconsistent property chain" );
        logProvider.formattedMessageMatcher().assertContains( value1.toString() );
        logProvider.formattedMessageMatcher().assertContains( value2.toString() );
        logProvider.formattedMessageMatcher().assertContains( value3.toString() );
    }

    private Value randomValueWithMaxSingleDynamicRecord()
    {
        Value value;
        PropertyBlock encoded;
        do
        {
            value = random.nextValue();
            encoded = encodedValue( 0, value );
        }
        while ( encoded.getValueRecords().size() > 1 );
        return value;
    }

    private List<PropertyBlock> getBlocksContainingDynamicValueRecords( long firstPropId )
    {
        long propId = firstPropId;
        List<PropertyBlock> blocks = new ArrayList<>();
        try ( PageCursor cursor = propertyStore.openPageCursorForReading( propId ) )
        {
            PropertyRecord record = propertyStore.newRecord();
            while ( !Record.NO_NEXT_PROPERTY.is( propId ) )
            {
                propertyStore.getRecordByCursor( propId, record, RecordLoad.NORMAL, cursor );
                propertyStore.ensureHeavy( record );
                for ( PropertyBlock block : record )
                {
                    if ( block.getValueRecords().size() > 1 )
                    {
                        blocks.add( block );
                    }
                }
                propId = record.getNextProp();
            }
        }
        return blocks;
    }

    private void makeSomeUnusedIn( PropertyBlock dynamicBlock )
    {
        propertyStore.ensureHeavy( dynamicBlock );
        DynamicRecord valueRecord = dynamicBlock.getValueRecords().get( random.nextInt( dynamicBlock.getValueRecords().size() ) );
        PropertyType type = valueRecord.getType();
        valueRecord.setInUse( false );
        AbstractDynamicStore dynamicStore = type == PropertyType.STRING ? propertyStore.getStringStore() : propertyStore.getArrayStore();
        dynamicStore.updateRecord( valueRecord );
    }

    private void createCycleIn( PropertyBlock dynamicBlock )
    {
        propertyStore.ensureHeavy( dynamicBlock );
        int cycleEndIndex = random.nextInt( 1, dynamicBlock.getValueRecords().size() - 1 );
        int cycleStartIndex = random.nextInt( cycleEndIndex );
        DynamicRecord cycleEndRecord = dynamicBlock.getValueRecords().get( cycleEndIndex );
        PropertyType type = cycleEndRecord.getType();
        cycleEndRecord.setNextBlock( dynamicBlock.getValueRecords().get( cycleStartIndex ).getId() );
        AbstractDynamicStore dynamicStore = type == PropertyType.STRING ? propertyStore.getStringStore() : propertyStore.getArrayStore();
        dynamicStore.updateRecord( cycleEndRecord );
    }

    private Value randomLargeLongArray()
    {
        long[] array = new long[200];
        for ( int i = 0; i < array.length; i++ )
        {
            array[i] = random.nextLong();
        }
        return Values.of( array );
    }

    private PropertyBlock encodedValue( int key, Value value )
    {
        PropertyBlock block = new PropertyBlock();
        propertyStore.encodeValue( block, key, value );
        return block;
    }

    private void readValuesFromPropertyRecord( PropertyRecord record, List<Value> values )
    {
        for ( PropertyBlock block : record )
        {
            values.add( block.getType().value( block, propertyStore ) );
        }
    }
}
