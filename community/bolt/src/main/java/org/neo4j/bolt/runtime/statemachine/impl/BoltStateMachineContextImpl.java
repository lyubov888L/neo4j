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
package org.neo4j.bolt.runtime.statemachine.impl;

import java.time.Clock;

import org.neo4j.bolt.BoltChannel;
import org.neo4j.bolt.runtime.BoltConnectionFatality;
import org.neo4j.bolt.runtime.statemachine.BoltStateMachine;
import org.neo4j.bolt.runtime.statemachine.BoltStateMachineSPI;
import org.neo4j.bolt.runtime.statemachine.MutableConnectionState;
import org.neo4j.bolt.runtime.statemachine.StateMachineContext;
import org.neo4j.bolt.runtime.statemachine.StatementProcessorReleaseManager;
import org.neo4j.bolt.security.auth.AuthenticationResult;
import org.neo4j.bolt.transaction.InitializeContext;
import org.neo4j.bolt.transaction.TransactionManager;
import org.neo4j.bolt.transaction.TransactionNotFoundException;
import org.neo4j.bolt.v41.messaging.RoutingContext;
import org.neo4j.kernel.database.DefaultDatabaseResolver;
import org.neo4j.memory.HeapEstimator;
import org.neo4j.memory.MemoryTracker;

public class BoltStateMachineContextImpl implements StateMachineContext, StatementProcessorReleaseManager
{
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance( BoltStateMachineContextImpl.class );

    private final BoltStateMachine machine;
    private final BoltChannel boltChannel;
    private final BoltStateMachineSPI spi;
    private final MutableConnectionState connectionState;
    private final Clock clock;
    private final DefaultDatabaseResolver defaultDatabaseResolver;
    private final TransactionManager transactionManager;
    private final MemoryTracker memoryTracker;

    public BoltStateMachineContextImpl( BoltStateMachine machine, BoltChannel boltChannel, BoltStateMachineSPI spi, MutableConnectionState connectionState,
                                        Clock clock, DefaultDatabaseResolver defaultDatabaseResolver,
                                        MemoryTracker memoryTracker, TransactionManager transactionManager )
    {
        this.machine = machine;
        this.boltChannel = boltChannel;
        this.spi = spi;
        this.connectionState = connectionState;
        this.clock = clock;
        this.memoryTracker = memoryTracker;
        this.defaultDatabaseResolver = defaultDatabaseResolver;
        this.transactionManager = transactionManager;
    }

    @Override
    public void authenticatedAsUser( String username, String userAgent )
    {
        boltChannel.updateUser( username, userAgent );
    }

    @Override
    public void resolveDefaultDatabase()
    {
        boltChannel.updateDefaultDatabase( defaultDatabaseResolver.defaultDatabase( boltChannel.username() ) );
    }

    @Override
    public void handleFailure( Throwable cause, boolean fatal ) throws BoltConnectionFatality
    {
        machine.handleFailure( cause, fatal );
    }

    @Override
    public boolean resetMachine() throws BoltConnectionFatality
    {
        return machine.reset();
    }

    @Override
    public BoltStateMachineSPI boltSpi()
    {
        return spi;
    }

    @Override
    public MutableConnectionState connectionState()
    {
        return connectionState;
    }

    @Override
    public Clock clock()
    {
        return clock;
    }

    @Override
    public String connectionId()
    {
        return machine.id();
    }

    @Override
    public void initStatementProcessorProvider( AuthenticationResult authResult, RoutingContext routingContext )
    {
        var transactionSpiProvider = spi.transactionStateMachineSPIProvider();
        var statementProcessorProvider = new StatementProcessorProvider( authResult, transactionSpiProvider, clock,
                                                                         this, routingContext, memoryTracker );
        var initializeContext = new InitializeContext( connectionId(), statementProcessorProvider );

        transactionManager.initialize( initializeContext );
    }

    @Override
    public TransactionManager getTransactionManager()
    {
        return transactionManager;
    }

    @Override
    public void releaseStatementProcessor( String transactionId )
    {
        try
        {
            // we handle the case where a program has been executed and released before
            // the transaction manager is made aware of it. So it is provided to the StatementProcessor
            // and used here if no transactionId has been set.
            if ( connectionState.getCurrentTransactionId() == null )
            {
                transactionManager.rollback( transactionId );
            }
            else
            {
                transactionManager.rollback( connectionState().getCurrentTransactionId() );
            }

        }
        catch ( TransactionNotFoundException e )
        {
            //ignore since the transaction must have already been removed successfully.
        }
        connectionState.clearCurrentTransactionId();
    }
}
