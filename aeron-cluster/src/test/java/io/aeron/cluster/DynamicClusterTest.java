/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;


import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.cluster.service.ConsensusModuleProxy;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.MutableInteger;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.EpochClock;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static io.aeron.Aeron.NULL_VALUE;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

@Ignore
public class DynamicClusterTest
{
    private static final long MAX_CATALOG_ENTRIES = 1024;
    private static final int MAX_MEMBER_COUNT = 4;
    private static final int STATIC_MEMBER_COUNT = 3;
    private static final int MESSAGE_COUNT = 10;
    private static final String MSG = "Hello World!";

    private static final String CLUSTER_MEMBERS = clusterMembersString();
    private static final String[] CLUSTER_MEMBERS_ENDPOINTS = clusterMembersEndpoints();
    private static final String CLUSTER_MEMBERS_STATUS_ENDPOINTS = clusterMembersStatusEndpoints();
    private static final String LOG_CHANNEL =
        "aeron:udp?term-length=64k|control-mode=manual|control=localhost:55550";
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8010";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8020";

    private final AtomicLong timeOffset = new AtomicLong();
    private final EpochClock epochClock = () -> System.currentTimeMillis() + timeOffset.get();

    private final CountDownLatch latchOne = new CountDownLatch(MAX_MEMBER_COUNT);
    private final CountDownLatch latchTwo = new CountDownLatch(MAX_MEMBER_COUNT - 1);

    private final EchoService[] echoServices = new EchoService[MAX_MEMBER_COUNT];
    private ClusteredMediaDriver[] clusteredMediaDrivers = new ClusteredMediaDriver[MAX_MEMBER_COUNT];
    private ClusteredServiceContainer[] containers = new ClusteredServiceContainer[MAX_MEMBER_COUNT];
    private MediaDriver clientMediaDriver;
    private AeronCluster client;

    private final MutableInteger responseCount = new MutableInteger();
    private final EgressListener egressMessageListener =
        (correlationId, clusterSessionId, timestamp, buffer, offset, length, header) -> responseCount.value++;

    @After
    public void after()
    {
        CloseHelper.close(client);
        CloseHelper.close(clientMediaDriver);

        if (null != clientMediaDriver)
        {
            clientMediaDriver.context().deleteAeronDirectory();
        }

        for (final ClusteredServiceContainer container : containers)
        {
            CloseHelper.close(container);
        }

        for (final ClusteredMediaDriver driver : clusteredMediaDrivers)
        {
            CloseHelper.close(driver);

            if (null != driver)
            {
                driver.mediaDriver().context().deleteAeronDirectory();
                driver.consensusModule().context().deleteDirectory();
                driver.archive().context().deleteArchiveDirectory();
            }
        }
    }

    @Test(timeout = 10_000)
    public void shouldQueryClusterMembers() throws Exception
    {
        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            startStaticNode(i, true);
        }

        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        final ClusterMembersInfo clusterMembersInfo = queryClusterMembers(leaderMemberId);

        assertThat(clusterMembersInfo.leaderMemberId, is(leaderMemberId));
        assertThat(clusterMembersInfo.passiveMembers, is(""));
        assertThat(clusterMembersInfo.activeMembers, is(CLUSTER_MEMBERS));
    }

    @Test(timeout = 10_000)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshots() throws Exception
    {
        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            startStaticNode(i, true);
        }

        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        final int dynamicMemberIndex = STATIC_MEMBER_COUNT;
        startDynamicNode(dynamicMemberIndex, true);

        Thread.sleep(1000);

        assertThat(roleOf(dynamicMemberIndex), is(Cluster.Role.FOLLOWER));

        final ClusterMembersInfo clusterMembersInfo = queryClusterMembers(leaderMemberId);

        assertThat(clusterMembersInfo.leaderMemberId, is(leaderMemberId));
        assertThat(clusterMembersInfo.passiveMembers, is(""));
    }

    @Test(timeout = 10_000)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshotsThenSend() throws Exception
    {
        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            startStaticNode(i, true);
        }

        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        final int dynamicMemberIndex = STATIC_MEMBER_COUNT;
        startDynamicNode(dynamicMemberIndex, true);

        Thread.sleep(1000);

        assertThat(roleOf(dynamicMemberIndex), is(Cluster.Role.FOLLOWER));

        startClient();

        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        msgBuffer.putStringWithoutLengthAscii(0, MSG);

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT);
        awaitMessageCountForService(dynamicMemberIndex, MESSAGE_COUNT);
    }

    @Test(timeout = 10_000)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshotsWithCatchup() throws Exception
    {
        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            startStaticNode(i, true);
        }

        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        startClient();

        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        msgBuffer.putStringWithoutLengthAscii(0, MSG);

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT);

        final int dynamicMemberIndex = STATIC_MEMBER_COUNT;
        startDynamicNode(dynamicMemberIndex, true);

        awaitMessageCountForService(dynamicMemberIndex, MESSAGE_COUNT);
    }

    private void startStaticNode(final int index, final boolean cleanStart)
    {
        echoServices[index] = new EchoService(index, latchOne, latchTwo);
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";

        final AeronArchive.Context archiveCtx = new AeronArchive.Context()
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100 + index)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .aeronDirectoryName(baseDirName);

        clusteredMediaDrivers[index] = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDirName, "archive"))
                .controlChannel(archiveCtx.controlRequestChannel())
                .controlStreamId(archiveCtx.controlRequestStreamId())
                .localControlChannel("aeron:ipc?term-length=64k")
                .localControlStreamId(archiveCtx.controlRequestStreamId())
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(cleanStart),
            new ConsensusModule.Context()
                .epochClock(epochClock)
                .errorHandler(Throwable::printStackTrace)
                .clusterMemberId(index)
                .clusterMembers(CLUSTER_MEMBERS)
                .aeronDirectoryName(aeronDirName)
                .clusterDir(new File(baseDirName, "consensus-module"))
                .ingressChannel("aeron:udp?term-length=64k")
                .logChannel(memberSpecificPort(LOG_CHANNEL, index))
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .archiveContext(archiveCtx.clone())
                .deleteDirOnStart(cleanStart));

        containers[index] = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveContext(archiveCtx.clone())
                .clusterDir(new File(baseDirName, "service"))
                .clusteredService(echoServices[index])
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .errorHandler(Throwable::printStackTrace));
    }

    private void startDynamicNode(final int index, final boolean cleanStart)
    {
        echoServices[index] = new EchoService(index, latchOne, latchTwo);
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";

        final AeronArchive.Context archiveCtx = new AeronArchive.Context()
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100 + index)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .aeronDirectoryName(baseDirName);

        clusteredMediaDrivers[index] = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDirName, "archive"))
                .controlChannel(archiveCtx.controlRequestChannel())
                .controlStreamId(archiveCtx.controlRequestStreamId())
                .localControlChannel("aeron:ipc?term-length=64k")
                .localControlStreamId(archiveCtx.controlRequestStreamId())
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(cleanStart),
            new ConsensusModule.Context()
                .epochClock(epochClock)
                .errorHandler(Throwable::printStackTrace)
                .clusterMemberId(NULL_VALUE)
                .clusterMembers("")
                .clusterMembersStatusEndpoints(CLUSTER_MEMBERS_STATUS_ENDPOINTS)
                .memberEndpoints(CLUSTER_MEMBERS_ENDPOINTS[index])
                .aeronDirectoryName(aeronDirName)
                .clusterDir(new File(baseDirName, "consensus-module"))
                .ingressChannel("aeron:udp?term-length=64k")
                .logChannel(memberSpecificPort(LOG_CHANNEL, index))
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .archiveContext(archiveCtx.clone())
                .deleteDirOnStart(cleanStart));

        containers[index] = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveContext(archiveCtx.clone())
                .clusterDir(new File(baseDirName, "service"))
                .clusteredService(echoServices[index])
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .errorHandler(Throwable::printStackTrace));
    }

    private void stopNode(final int index)
    {
        containers[index].close();
        containers[index] = null;
        clusteredMediaDrivers[index].close();
        clusteredMediaDrivers[index] = null;
    }

    private void startClient()
    {
        final String aeronDirName = CommonContext.getAeronDirectoryName();

        clientMediaDriver = MediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .aeronDirectoryName(aeronDirName));

        client = AeronCluster.connect(
            new AeronCluster.Context()
                .egressListener(egressMessageListener)
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints("0=localhost:20110,1=localhost:20111,2=localhost:20112"));
    }

    private void sendMessages(final ExpandableArrayBuffer msgBuffer)
    {
        for (int i = 0; i < MESSAGE_COUNT; i++)
        {
            final long msgCorrelationId = client.nextCorrelationId();
            while (client.offer(msgCorrelationId, msgBuffer, 0, MSG.length()) < 0)
            {
                TestUtil.checkInterruptedStatus();
                client.pollEgress();
                Thread.yield();
            }

            client.pollEgress();
        }
    }

    private void awaitResponses(final int messageCount)
    {
        while (responseCount.get() < messageCount)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
            client.pollEgress();
        }
    }

    private void awaitMessageCountForService(final int index, final int messageCount)
    {
        while (echoServices[index].messageCount() < messageCount)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }
    }

    private static String memberSpecificPort(final String channel, final int memberId)
    {
        return channel.substring(0, channel.length() - 1) + memberId;
    }

    private static String clusterMembersString()
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            builder
                .append(i).append(',')
                .append("localhost:2011").append(i).append(',')
                .append("localhost:2022").append(i).append(',')
                .append("localhost:2033").append(i).append(',')
                .append("localhost:2044").append(i).append(',')
                .append("localhost:801").append(i).append('|');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    private static String[] clusterMembersEndpoints()
    {
        final String[] clusterMembersEndpoints = new String[MAX_MEMBER_COUNT];

        for (int i = 0; i < MAX_MEMBER_COUNT; i++)
        {
            final StringBuilder builder = new StringBuilder();

            builder
                .append("localhost:2011").append(i).append(',')
                .append("localhost:2022").append(i).append(',')
                .append("localhost:2033").append(i).append(',')
                .append("localhost:2044").append(i).append(',')
                .append("localhost:801").append(i);

            clusterMembersEndpoints[i] = builder.toString();
        }

        return clusterMembersEndpoints;
    }

    private static String clusterMembersStatusEndpoints()
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < STATIC_MEMBER_COUNT; i++)
        {
            builder.append("localhost:2022").append(i).append(',');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    static class EchoService extends StubClusteredService
    {
        private volatile int messageCount;
        private final int index;
        private final CountDownLatch latchOne;
        private final CountDownLatch latchTwo;

        EchoService(final int index, final CountDownLatch latchOne, final CountDownLatch latchTwo)
        {
            this.index = index;
            this.latchOne = latchOne;
            this.latchTwo = latchTwo;
        }

        int index()
        {
            return index;
        }

        int messageCount()
        {
            return messageCount;
        }

        public void onSessionMessage(
            final ClientSession session,
            final long correlationId,
            final long timestampMs,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            while (session.offer(correlationId, buffer, offset, length) < 0)
            {
                cluster.idle();
            }

            ++messageCount;

            if (messageCount == MESSAGE_COUNT)
            {
                latchOne.countDown();
            }

            if (messageCount == (MESSAGE_COUNT * 2))
            {
                latchTwo.countDown();
            }
        }
    }

    private int findLeaderId(final int skipMemberId)
    {
        int leaderMemberId = NULL_VALUE;

        for (int i = 0; i < 3; i++)
        {
            if (i == skipMemberId)
            {
                continue;
            }

            final ClusteredMediaDriver driver = clusteredMediaDrivers[i];

            if (null == driver)
            {
                continue;
            }

            final Cluster.Role role = Cluster.Role.get(
                (int)driver.consensusModule().context().clusterNodeCounter().get());

            if (Cluster.Role.LEADER == role)
            {
                leaderMemberId = driver.consensusModule().context().clusterMemberId();
            }
        }

        return leaderMemberId;
    }

    private static class ClusterMembersInfo
    {
        int leaderMemberId = NULL_VALUE;
        String activeMembers = null;
        String passiveMembers = null;
    }

    private ClusterMembersInfo queryClusterMembers(final int index) throws Exception
    {
        final ConsensusModule.Context consensusModuleContext =
            clusteredMediaDrivers[index].consensusModule().context();

        // to consensus module - serviceControlChannel, consensusModuleStreamId
        // to services - serviceControlChannel, serviceStreamId
        final String channel = consensusModuleContext.serviceControlChannel();
        final int toConsensusModuleStreamId = consensusModuleContext.consensusModuleStreamId();
        final int toServiceStreamId = consensusModuleContext.serviceStreamId();

        final MutableLong id = new MutableLong(NULL_VALUE);
        final ClusterMembersInfo members = new ClusterMembersInfo();

        final MemberServiceAdapter.MemberServiceHandler handler =
            new MemberServiceAdapter.MemberServiceHandler()
            {
                public void onClusterMembersResponse(
                    final long correlationId,
                    final int leaderMemberId,
                    final String activeMembers,
                    final String passiveMembers)
                {
                    if (correlationId == id.longValue())
                    {
                        members.leaderMemberId = leaderMemberId;
                        members.activeMembers = activeMembers;
                        members.passiveMembers = passiveMembers;
                    }
                }
            };

        try (
            Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(consensusModuleContext.aeronDirectoryName()));
            ConsensusModuleProxy consensusModuleProxy =
                new ConsensusModuleProxy(aeron.addPublication(channel, toConsensusModuleStreamId));
            MemberServiceAdapter memberServiceAdapter =
                new MemberServiceAdapter(aeron.addSubscription(channel, toServiceStreamId), handler))
        {
            id.set(aeron.nextCorrelationId());
            if (consensusModuleProxy.clusterMembersQuery(id.longValue()))
            {
                do
                {
                    if (memberServiceAdapter.poll() == 0)
                    {
                        Thread.sleep(1);
                    }
                }
                while (null == members.activeMembers);
            }
        }

        return members;
    }

    private Cluster.Role roleOf(final int index)
    {
        final ClusteredMediaDriver driver = clusteredMediaDrivers[index];

        return Cluster.Role.get(
            (int)driver.consensusModule().context().clusterNodeCounter().get());
    }
}
