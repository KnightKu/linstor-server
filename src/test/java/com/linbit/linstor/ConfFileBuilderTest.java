package com.linbit.linstor;

import com.linbit.ImplementationError;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.prop.WhitelistProps;
import com.linbit.linstor.dbdrivers.satellite.SatellitePropDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.InvalidValueException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.propscon.PropsContainer;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.DummySecurityInitializer;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.stateflags.StateFlags;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.layer.adapter.drbd.utils.ConfFileBuilder;
import com.linbit.linstor.testutils.EmptyErrorReporter;
import com.linbit.linstor.transaction.SatelliteTransactionMgr;
import com.linbit.linstor.transaction.TransactionMgr;

import javax.inject.Provider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

public class ConfFileBuilderTest
{
    private ErrorReporter errorReporter;
    private AccessContext accessContext;

    private ObjectProtection dummyObjectProtection;
    private WhitelistProps whitelistProps;

    private Resource localRsc, peerRsc;

    private ConfFileBuilder confFileBuilder;
    private ResourceConnection rscConn;
    private NodeConnection nodeConn;
    private PropsContainer props;

    @Before
    public void setUp() throws Exception
    {
        MockitoAnnotations.initMocks(this);

        errorReporter = new EmptyErrorReporter();
        accessContext = DummySecurityInitializer.getSystemAccessContext();

        dummyObjectProtection = DummySecurityInitializer.getDummyObjectProtection(accessContext);

        whitelistProps = new WhitelistProps(errorReporter);
        // just let it empty - we do not want test drbd-options anyways here

        TransactionMgr dummyTransMgr = new SatelliteTransactionMgr();
        Provider<TransactionMgr> transMgrProvider = () -> dummyTransMgr;
        props = new PropsContainerFactory(
                new SatellitePropDriver(), transMgrProvider).getInstance("TESTINSTANCE");
        localRsc = makeMockResource(101, "alpha", "1.2.3.4", false, false, false);
        peerRsc = makeMockResource(202, "bravo", "5.6.7.8", false, false, false);
        when(localRsc.getAssignedNode().getNodeConnection(
                accessContext, peerRsc.getAssignedNode())).thenReturn(nodeConn);
        when(peerRsc.getAssignedNode().getNodeConnection(
                accessContext, localRsc.getAssignedNode())).thenReturn(nodeConn);
        when(rscConn.getProps(accessContext)).thenReturn(props);
        when(nodeConn.getProps(accessContext)).thenReturn(props);
        when(localRsc.getResourceConnection(accessContext, peerRsc)).thenReturn(rscConn);
        when(peerRsc.getResourceConnection(accessContext, localRsc)).thenReturn(rscConn);
    }

    private void setProps(String[] nodeNames, String... nicNames) throws SQLException, InvalidValueException, InvalidKeyException
    {
        assertThat(nodeNames.length == 2).isTrue();
        assertThat(nicNames.length == 4).isTrue();
        props.setProp("1/" + nodeNames[0], nicNames[0], "Paths");
        props.setProp("1/" + nodeNames[1], nicNames[1], "Paths");
        props.setProp("2/" + nodeNames[0], nicNames[2], "Paths");
        props.setProp("2/" + nodeNames[1], nicNames[3], "Paths");
    }

    @Test(expected = ImplementationError.class)
    public void testPeerRscsNull() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                null,
                whitelistProps
        );

        confFileBuilder.build();
    }

    @Test
    public void testPeerRscsEmpty() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.emptyList(),
                whitelistProps
        );

        assertThat(confFileBuilder.build().contains("connection\n")).isFalse();
    }

    @Test(expected = ImplementationError.class)
    public void testRscDfnNull() throws Exception
    {
        when(localRsc.getDefinition()).thenReturn(null);
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );

        confFileBuilder.build();
    }

    @Test
    public void testRscConnNull() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );

        String confFile = confFileBuilder.build();
        assertThat(confFile.contains("path\n")).isFalse();
        assertThat(confFile.contains("1.2.3.4") && confFile.contains("5.6.7.8")).isTrue();
    }

    @Test(expected = ImplementationError.class)
    public void testNoNodesConfigured() throws Exception
    {
        setProps(new String[] {"alpha", "alpha"}, "eth0", "eth1", "eth2", "eth3");
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );

        confFileBuilder.build();
    }

    @Test(expected = ImplementationError.class)
    public void testNodeNamesNotMatching() throws Exception
    {
        setProps(new String[] {"charlie", "delta"}, "eth0", "eth1", "eth2", "eth3");
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );

        confFileBuilder.build();
    }

    @Test(expected = StorageException.class)
    public void testUnknownNicName() throws Exception
    {
        setProps(new String[] {"alpha", "bravo"}, "eth-1", "eth1", "eth2", "eth3");
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );

        confFileBuilder.build();
    }

    @Test(expected = StorageException.class)
    public void testInvalidName()
            throws SQLException, InvalidKeyException, InvalidValueException, AccessDeniedException, StorageException
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        setProps(new String[] {"alpha", "bravo"}, "666", "666", "666", "666");

        confFileBuilder.build();
    }

    @Test
    public void testNewFormat() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        setProps(new String[] {"alpha", "bravo"}, "eth0", "eth1", "eth2", "eth3");

        String confFile = confFileBuilder.build();
        assertThat(confFile.contains("path\n") && confFile.contains("1.2.3.4") && confFile.contains("5.6.7.8"))
                .isTrue();
    }
/*
    @Test(expected = ImplementationError.class)
    public void testInvalidKey()
            throws SQLException, InvalidKeyException, InvalidValueException, AccessDeniedException, StorageException
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        setProps(new String[] {"alpha", "bravo"}, null, null, null, null);
        confFileBuilder.build();
    }
*/
    @SuppressWarnings("checkstyle:magicnumber")
    private Resource makeMockResource(
            final int volumeNumber,
            final String nodeName,
            final String ipAddr,
            final boolean volumeDeleted,
            final boolean resourceDeleted,
            final boolean diskless
    )
            throws Exception
    {
        Resource resource = Mockito.mock(Resource.class);
        ResourceDefinition resourceDefinition = Mockito.mock(ResourceDefinition.class);
        StateFlags<Resource.RscFlags> rscStateFlags = Mockito.mock(ResourceStateFlags.class);
        StateFlags<ResourceConnection.RscConnFlags> rscConnStateFlags = Mockito.mock(ResourceConnStateFlags.class);
        Node assignedNode = Mockito.mock(Node.class);
        NetInterface netInterface = Mockito.mock(NetInterface.class);
        Volume volume = Mockito.mock(Volume.class);
        StateFlags<Volume.VlmFlags> volumeFlags = Mockito.mock(VolumeStateFlags.class);
        VolumeDefinition volumeDefinition = Mockito.mock(VolumeDefinition.class);
        StorPool storPool = Mockito.mock(StorPool.class);
        rscConn = Mockito.mock(ResourceConnectionData.class);
        nodeConn = Mockito.mock(NodeConnectionData.class);

        Props storPoolProps = Mockito.mock(Props.class);
        Props vlmProps = Mockito.mock(Props.class);
        Props vlmDfnProps = Mockito.mock(Props.class);
        Props rscProps = Mockito.mock(Props.class);
        Props nodeProps = Mockito.mock(Props.class);
        Props rscDfnProps = Mockito.mock(Props.class);
        Optional<Props> drbdprops = Optional.empty();

        when(storPool.getProps(accessContext)).thenReturn(storPoolProps);

        when(volumeDefinition.getVolumeNumber()).thenReturn(new VolumeNumber(volumeNumber));
        when(volumeDefinition.getMinorNr(any(AccessContext.class))).thenReturn(new MinorNumber(99));

        when(
                volumeFlags.isUnset(
                        any(AccessContext.class),
                        eq(Volume.VlmFlags.DELETE)
                )
        ).thenReturn(!volumeDeleted);

        when(volume.getFlags()).thenReturn(volumeFlags);
        when(volume.getVolumeDefinition()).thenReturn(volumeDefinition);
        when(volume.getStorPool(accessContext)).thenReturn(storPool);
        when(volume.getProps(accessContext)).thenReturn(vlmProps);
        when(volume.getBackingDiskPath(accessContext)).thenReturn("/dev/foo");
        when(volume.getResource()).thenReturn(resource);

        when(netInterface.getAddress(any(AccessContext.class)))
                .thenReturn(new LsIpAddress(ipAddr));

        when(assignedNode.getName()).thenReturn(new NodeName(nodeName));
        when(assignedNode.streamNetInterfaces(any(AccessContext.class)))
                .thenAnswer(makeStreamAnswer(netInterface));

        when(assignedNode.getNetInterface(
                accessContext, new NetInterfaceName("eth0"))).thenReturn(netInterface);
        when(assignedNode.getNetInterface(
                accessContext, new NetInterfaceName("eth1"))).thenReturn(netInterface);
        when(assignedNode.getNetInterface(
                accessContext, new NetInterfaceName("eth2"))).thenReturn(netInterface);
        when(assignedNode.getNetInterface(
                accessContext, new NetInterfaceName("eth3"))).thenReturn(netInterface);
        when(assignedNode.getNetInterface(
                accessContext, new NetInterfaceName("eth-1"))).thenReturn(null);
        when(netInterface.getNode()).thenReturn(assignedNode);

        when(rscStateFlags.isUnset(any(AccessContext.class), eq(Resource.RscFlags.DELETE)))
                .thenReturn(!resourceDeleted);
        when(
                rscStateFlags.isUnset(any(AccessContext.class), eq(Resource.RscFlags.DISKLESS)))
                .thenReturn(!diskless);
        when(
                rscStateFlags.isSet(any(AccessContext.class), eq(Resource.RscFlags.DISKLESS)))
                .thenReturn(diskless);

        when(resourceDefinition.getName()).thenReturn(new ResourceName("testResource"));
        when(resourceDefinition.getProps(accessContext)).thenReturn(rscDfnProps);
        when(rscDfnProps.getNamespace(any(String.class))).thenReturn(Optional.empty());
        when(resourceDefinition.getPort(any(AccessContext.class)))
                .thenReturn(new TcpPortNumber(42));

        when(volumeDefinition.getProps(accessContext)).thenReturn(vlmDfnProps);
        when(vlmDfnProps.getNamespace(ApiConsts.NAMESPC_DRBD_DISK_OPTIONS)).thenReturn(drbdprops);

        when(resource.getObjProt()).thenReturn(dummyObjectProtection);
        when(resource.getDefinition()).thenReturn(resourceDefinition);
        when(resource.getStateFlags()).thenReturn(rscStateFlags);
        when(rscConn.getStateFlags()).thenReturn(rscConnStateFlags);
        when(resource.getAssignedNode()).thenReturn(assignedNode);
        when(resource.iterateVolumes()).thenAnswer(makeIteratorAnswer(volume));
        when(resource.getNodeId()).thenReturn(new NodeId(12));
        when(resource.getProps(accessContext)).thenReturn(rscProps);
        when(resource.disklessForPeers(accessContext)).thenReturn(diskless);

        when(assignedNode.getProps(accessContext)).thenReturn(nodeProps);

        return resource;
    }

    @SuppressWarnings("checkstyle:magicnumber")
    @Test
    public void testMatchingBraceCounts() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
            errorReporter,
            accessContext,
            localRsc,
            Collections.singletonList(peerRsc),
            whitelistProps
        );
        String confFile = confFileBuilder.build();

        int leftBraceCount = countOccurrences(confFile, "\\{");
        int rightBraceCount = countOccurrences(confFile, "\\}");

        assertThat(leftBraceCount).isEqualTo(rightBraceCount);
    }

    @SuppressWarnings("checkstyle:magicnumber")
    @Test
    public void testKeywordOccurrences() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        String confFile = confFileBuilder.build();

        assertThat(countOccurrences(confFile, "^ *resource")).isEqualTo(1);
        assertThat(countOccurrences(confFile, "^ *cram-hmac-alg ")).isEqualTo(1);
        assertThat(countOccurrences(confFile, "^ *shared-secret ")).isEqualTo(1);
        assertThat(countOccurrences(confFile, "^ *on ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *volume ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *disk ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *meta-disk ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *device ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *node-id ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, "^ *connection")).isEqualTo(1);
        assertThat(countOccurrences(confFile, "^ *host ")).isEqualTo(2);
        assertThat(countOccurrences(confFile, " address ")).isEqualTo(2);
    }

    @SuppressWarnings("checkstyle:magicnumber")
    @Test
    public void testDeletedVolume() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        String confFileNormal = confFileBuilder.build();

        String confFileDeleted = new ConfFileBuilder(
            errorReporter,
            accessContext,
            makeMockResource(101, "testNode", "1.2.3.4", true, false, false),
            Collections.singletonList(makeMockResource(202, "testNode", "5.6.7.8", true, false, false)),
            whitelistProps
        ).build();

        assertThat(countOccurrences(confFileNormal, "^ *volume ")).isEqualTo(2);
        assertThat(countOccurrences(confFileDeleted, "^ *volume ")).isEqualTo(0);
    }

    @SuppressWarnings("checkstyle:magicnumber")
    @Test
    public void testDeletedPeerResource() throws Exception
    {
        confFileBuilder = new ConfFileBuilder(
                errorReporter,
                accessContext,
                localRsc,
                Collections.singletonList(peerRsc),
                whitelistProps
        );
        String confFileNormal = confFileBuilder.build();

        String confFileDeleted = new ConfFileBuilder(
            errorReporter,
            accessContext,
            makeMockResource(101, "testNode", "1.2.3.4", false, false, false),
            Collections.singletonList(makeMockResource(202, "testNode", "5.6.7.8", false, true, false)),
            whitelistProps
        ).build();

        assertThat(countOccurrences(confFileNormal, "^ *on ")).isEqualTo(2);
        assertThat(countOccurrences(confFileNormal, "^ *connection")).isEqualTo(1);
        assertThat(countOccurrences(confFileDeleted, "^ *on ")).isEqualTo(1);
        assertThat(countOccurrences(confFileDeleted, "^ *connection")).isEqualTo(0);
    }

    @Test
    public void testNoConnectionBetweenDiskless() throws Exception
    {
        {
            List<Resource> peerRscs = new ArrayList<>();
            peerRscs.add(makeMockResource(0, "testNode1", "5.6.7.8", false, false, true));
            peerRscs.add(makeMockResource(0, "testNode2", "9.10.11.12", false, false, true));
            String confFileNormal = new ConfFileBuilder(
                errorReporter,
                accessContext,
                makeMockResource(0, "localNode", "1.2.3.4", false, false, false),
                peerRscs,
                whitelistProps
            ).build();

            assertThat(countOccurrences(confFileNormal, "^ *connection")).isEqualTo(2);
        }

        {
            List<Resource> peerRscs = new ArrayList<>();
            peerRscs.add(makeMockResource(0, "testNode1", "5.6.7.8", false, false, false));
            peerRscs.add(makeMockResource(0, "testNode2", "9.10.11.12", false, false, true));
            String confFileNormal = new ConfFileBuilder(
                errorReporter,
                accessContext,
                makeMockResource(0, "localNode", "1.2.3.4", false, false, true),
                peerRscs,
                whitelistProps
            ).build();

            assertThat(countOccurrences(confFileNormal, "^ *connection")).isEqualTo(1);
        }

        {
            List<Resource> peerRscs = new ArrayList<>();
            peerRscs.add(makeMockResource(0, "testNode1", "5.6.7.8", false, false, false));
            peerRscs.add(makeMockResource(0, "testNode2", "9.10.11.12", false, false, false));
            String confFileNormal = new ConfFileBuilder(
                errorReporter,
                accessContext,
                makeMockResource(0, "localNode", "1.2.3.4", false, false, false),
                peerRscs,
                whitelistProps
            ).build();

            assertThat(countOccurrences(confFileNormal, "^ *connection")).isEqualTo(2);
        }
    }

    private int countOccurrences(final String str, final String regex)
    {
        Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(str);

        int count = 0;
        while (matcher.find())
        {
            count++;
        }

        return count;
    }

    @SafeVarargs
    private final <T> Answer<Iterator<T>> makeIteratorAnswer(final T... ts)
    {
        return new Answer<Iterator<T>>()
        {
            @Override
            public Iterator<T> answer(final InvocationOnMock invocation)
            {
                return Arrays.asList(ts).iterator();
            }
        };
    }

    @SafeVarargs
    private final <T> Answer<Stream<T>> makeStreamAnswer(final T... ts)
    {
        return new Answer<Stream<T>>()
        {
            @Override
            public Stream<T> answer(InvocationOnMock invocation) throws Throwable
            {
                return Arrays.asList(ts).stream();
            }
        };
    }

    private interface ResourceStateFlags extends StateFlags<Resource.RscFlags>
    {
    }

    private interface ResourceConnStateFlags extends StateFlags<ResourceConnection.RscConnFlags>
    {
    }

    private interface VolumeStateFlags extends StateFlags<Volume.VlmFlags>
    {
    }

}
