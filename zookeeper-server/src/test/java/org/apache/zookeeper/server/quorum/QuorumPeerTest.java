/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

public class QuorumPeerTest {

    private int electionAlg = 3;
    private int tickTime = 2000;
    private int initLimit = 3;
    private int syncLimit = 3;
    private int connectToLearnerMasterLimit = 3;

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2301
     */
    @Test
    public void testQuorumPeerListendOnSpecifiedClientIP(@TempDir File dataDir) throws IOException {
        long myId = 1;
        int clientPort = PortAssignment.unique();
        Map<Long, QuorumServer> peersView = new HashMap<>();
        InetAddress clientIP = InetAddress.getLoopbackAddress();

        peersView.put(Long.valueOf(myId), new QuorumServer(myId, new InetSocketAddress(clientIP, PortAssignment.unique()), new InetSocketAddress(clientIP, PortAssignment.unique()), new InetSocketAddress(clientIP, clientPort), LearnerType.PARTICIPANT));

        /**
         * QuorumPeer constructor without QuorumVerifier
         */
        QuorumPeer peer1 = new QuorumPeer(peersView, dataDir, dataDir, clientPort, electionAlg, myId, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        String hostString1 = peer1.cnxnFactory.getLocalAddress().getHostString();
        assertEquals(clientIP.getHostAddress(), hostString1);

        // cleanup
        peer1.shutdown();

        /**
         * QuorumPeer constructor with QuorumVerifier
         */
        peersView.clear();
        clientPort = PortAssignment.unique();
        peersView.put(Long.valueOf(myId), new QuorumServer(myId, new InetSocketAddress(clientIP, PortAssignment.unique()), new InetSocketAddress(clientIP, PortAssignment.unique()), new InetSocketAddress(clientIP, clientPort), LearnerType.PARTICIPANT));
        QuorumPeer peer2 = new QuorumPeer(peersView, dataDir, dataDir, clientPort, electionAlg, myId, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        String hostString2 = peer2.cnxnFactory.getLocalAddress().getHostString();
        assertEquals(clientIP.getHostAddress(), hostString2);
        // cleanup
        peer2.shutdown();
    }

    @Test
    public void testProcessReconfigWillRecreateSocketAddresses() throws IOException {
        QuorumPeerConfig.setReconfigEnabled(true);
        QuorumPeer peer = new QuorumPeer();

        File file = Files.createTempFile("", ".tmp").toFile();
        file.deleteOnExit();

        peer.setConfigFileName(file.getAbsoluteFile().toString());

        Map<Long, QuorumServer> peersView = new HashMap<>();
        QuorumServer qs = Mockito.mock(QuorumServer.class);
        peersView.put(0L, qs);

        QuorumVerifier qv = new QuorumMaj(peersView);
        peer.setQuorumVerifier(qv, false);
        peer.processReconfig(qv, 0L, 0L, false);

        // verify the qs will recreateSocketAddresses twice for both the old qv and the new qv
        Mockito.verify(qs, Mockito.times(2)).recreateSocketAddresses();
    }

    @Test
    public void testLocalPeerIsLeader() throws Exception {
        long localPeerId = 7;
        QuorumPeer peer = new QuorumPeer();
        peer.setId(localPeerId);
        Vote voteLocalPeerIsLeader = new Vote(localPeerId, 0);
        peer.setCurrentVote(voteLocalPeerIsLeader);
        assertTrue(peer.isLeader(localPeerId));
    }

    @Test
    public void testLocalPeerIsNotLeader() throws Exception {
        long localPeerId = 7;
        long otherPeerId = 17;
        QuorumPeer peer = new QuorumPeer();
        peer.setId(localPeerId);
        Vote voteLocalPeerIsNotLeader = new Vote(otherPeerId, 0);
        peer.setCurrentVote(voteLocalPeerIsNotLeader);
        assertFalse(peer.isLeader(localPeerId));
    }

    @Test
    public void testIsNotLeaderBecauseNoVote() throws Exception {
        long localPeerId = 7;
        QuorumPeer peer = new QuorumPeer();
        peer.setId(localPeerId);
        peer.setCurrentVote(null);
        assertFalse(peer.isLeader(localPeerId));
    }

}
