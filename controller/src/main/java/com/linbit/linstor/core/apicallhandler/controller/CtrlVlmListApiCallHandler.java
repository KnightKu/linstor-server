package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.linstor.LinstorParsingUtils;
import com.linbit.linstor.Node;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.NodeRepository;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceConnection;
import com.linbit.linstor.ResourceData;
import com.linbit.linstor.ResourceDefinitionRepository;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.StorPoolName;
import com.linbit.linstor.Volume;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.ApiModule;
import com.linbit.linstor.api.interfaces.serializer.CtrlClientSerializer;
import com.linbit.linstor.api.pojo.RscPojo;
import com.linbit.linstor.core.CoreModule;
import com.linbit.linstor.core.apicallhandler.ScopeRunner;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.satellitestate.SatelliteState;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.StorageDriverKind;
import com.linbit.locks.LockGuard;
import reactor.core.publisher.Flux;
import reactor.util.function.Tuple2;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Singleton
public class CtrlVlmListApiCallHandler
{
    private final ErrorReporter errorReporter;
    private final ScopeRunner scopeRunner;
    private final VlmAllocatedFetcher vlmAllocatedFetcher;
    private final ResourceDefinitionRepository resourceDefinitionRepository;
    private final NodeRepository nodeRepository;
    private final CtrlClientSerializer clientComSerializer;
    private final ReadWriteLock nodesMapLock;
    private final ReadWriteLock rscDfnMapLock;
    private final Provider<AccessContext> peerAccCtx;
    private final Provider<Long> apiCallId;

    @Inject
    public CtrlVlmListApiCallHandler(
        ErrorReporter errorReporterRef,
        ScopeRunner scopeRunnerRef,
        VlmAllocatedFetcher vlmAllocatedFetcherRef,
        ResourceDefinitionRepository resourceDefinitionRepositoryRef,
        NodeRepository nodeRepositoryRef,
        CtrlClientSerializer clientComSerializerRef,
        @Named(CoreModule.NODES_MAP_LOCK) ReadWriteLock nodesMapLockRef,
        @Named(CoreModule.RSC_DFN_MAP_LOCK) ReadWriteLock rscDfnMapLockRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        @Named(ApiModule.API_CALL_ID) Provider<Long> apiCallIdRef
    )
    {
        errorReporter = errorReporterRef;
        scopeRunner = scopeRunnerRef;
        vlmAllocatedFetcher = vlmAllocatedFetcherRef;
        resourceDefinitionRepository = resourceDefinitionRepositoryRef;
        nodeRepository = nodeRepositoryRef;
        clientComSerializer = clientComSerializerRef;
        nodesMapLock = nodesMapLockRef;
        rscDfnMapLock = rscDfnMapLockRef;
        peerAccCtx = peerAccCtxRef;
        apiCallId = apiCallIdRef;
    }

    public Flux<byte[]> listVlms(
        List<String> nodeNames,
        List<String> storPools,
        List<String> resources
    )
    {
        final Set<NodeName> nodesFilter =
            nodeNames.stream().map(LinstorParsingUtils::asNodeName).collect(Collectors.toSet());
        final Set<StorPoolName> storPoolsFilter =
            storPools.stream().map(LinstorParsingUtils::asStorPoolName).collect(Collectors.toSet());
        final Set<ResourceName> resourceFilter =
            resources.stream().map(LinstorParsingUtils::asRscName).collect(Collectors.toSet());

        return vlmAllocatedFetcher.fetchVlmAllocated(nodesFilter, storPoolsFilter, resourceFilter)
            .flatMapMany(vlmAllocatedAnswers ->
                scopeRunner.fluxInTransactionlessScope(
                    "Assemble volume list",
                    LockGuard.createDeferred(nodesMapLock.readLock(), rscDfnMapLock.readLock()),
                    () -> assembleList(nodesFilter, storPoolsFilter, resourceFilter, vlmAllocatedAnswers)
                )
            );
    }

    public Flux<byte[]> assembleList(
        Set<NodeName> nodesFilter,
        Set<StorPoolName> storPoolsFilter,
        Set<ResourceName> resourceFilter,
        Tuple2<Map<Volume.Key, Long>, List<ApiCallRc>> vlmAllocatedAnswers
    )
    {
        ArrayList<ResourceData.RscApi> rscs = new ArrayList<>();
        Map<NodeName, SatelliteState> satelliteStates = new HashMap<>();
        final Map<Volume.Key, Long> vlmAllocatedCapacities = vlmAllocatedAnswers.getT1();
        try
        {
            resourceDefinitionRepository.getMapForView(peerAccCtx.get()).values().stream()
                .filter(rscDfn -> resourceFilter.isEmpty() || resourceFilter.contains(rscDfn.getName()))
                .forEach(rscDfn ->
                {
                    try
                    {
                        for (Resource rsc : rscDfn.streamResource(peerAccCtx.get())
                            .filter(rsc -> nodesFilter.isEmpty() ||
                                nodesFilter.contains(rsc.getAssignedNode().getName()))
                            .collect(toList()))
                        {
                            // create our api object ourselves to filter the volumes by storage pools

                            // build volume list filtered by storage pools (if provided)
                            List<Volume.VlmApi> volumes = new ArrayList<>();
                            Iterator<Volume> itVolumes = rsc.iterateVolumes();
                            while (itVolumes.hasNext())
                            {
                                Volume vlm = itVolumes.next();
                                if (storPoolsFilter.isEmpty() ||
                                    storPoolsFilter.contains(vlm.getStorPool(peerAccCtx.get()).getName()))
                                {
                                    volumes.add(vlm.getApiData(
                                        getAllocated(vlmAllocatedCapacities, vlm),
                                        peerAccCtx.get()
                                    ));
                                }
                            }

                            List<ResourceConnection.RscConnApi> rscConns = new ArrayList<>();
                            for (ResourceConnection rscConn : rsc.streamResourceConnections(peerAccCtx.get())
                                    .collect(toList()))
                            {
                                rscConns.add(rscConn.getApiData(peerAccCtx.get()));
                            }

                            if (!volumes.isEmpty())
                            {
                                RscPojo filteredRscVlms = new RscPojo(
                                    rscDfn.getName().getDisplayName(),
                                    rsc.getAssignedNode().getName().getDisplayName(),
                                    rsc.getAssignedNode().getUuid(),
                                    rscDfn.getApiData(peerAccCtx.get()),
                                    rsc.getUuid(),
                                    rsc.getStateFlags().getFlagsBits(peerAccCtx.get()),
                                    rsc.getNodeId().value,
                                    rsc.getProps(peerAccCtx.get()).map(),
                                    volumes,
                                    null,
                                    rscConns,
                                    null,
                                    null);
                                rscs.add(filteredRscVlms);
                            }
                        }
                    }
                    catch (AccessDeniedException accDeniedExc)
                    {
                        // don't add rsc without access
                    }
                }
                );

            // get resource states of all nodes
            for (final Node node : nodeRepository.getMapForView(peerAccCtx.get()).values())
            {
                final Peer satellite = node.getPeer(peerAccCtx.get());
                if (satellite != null)
                {
                    Lock readLock = satellite.getSatelliteStateLock().readLock();
                    readLock.lock();
                    try
                    {
                        final SatelliteState satelliteState = satellite.getSatelliteState();

                        if (satelliteState != null)
                        {
                            satelliteStates.put(node.getName(), new SatelliteState(satelliteState));
                        }
                    }
                    finally
                    {
                        readLock.unlock();
                    }
                }
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            // for now return an empty list.
            errorReporter.reportError(accDeniedExc);
        }

        Flux<byte[]> flux =  Flux.just(
            clientComSerializer
                .answerBuilder(ApiConsts.API_LST_VLM, apiCallId.get())
                .resourceList(rscs, satelliteStates)
                .build()
        );

        for (ApiCallRc apiCallRc : vlmAllocatedAnswers.getT2())
        {
            flux = flux.concatWith(Flux.just(clientComSerializer
                .answerBuilder(ApiConsts.API_REPLY, apiCallId.get())
                .apiCallRcSeries(apiCallRc)
                .build())
            );
        }

        return flux;
    }

    private Long getAllocated(Map<Volume.Key, Long> vlmAllocatedCapacities, Volume vlm)
        throws AccessDeniedException
    {
        Long allocated;
        StorageDriverKind driverKind = vlm.getStorPool(peerAccCtx.get()).getDriverKind();
        if (driverKind.hasBackingStorage())
        {
            allocated = getDiskAllocated(vlmAllocatedCapacities, vlm);
        }
        else
        {
            // Report the maximum usage of the peer volumes for diskless volumes
            Long maxAllocated = null;
            Iterator<Volume> vlmIter = vlm.getVolumeDefinition().iterateVolumes(peerAccCtx.get());
            while (vlmIter.hasNext())
            {
                Volume peerVlm = vlmIter.next();
                Long peerAllocated = getDiskAllocated(vlmAllocatedCapacities, peerVlm);
                if (peerAllocated != null && (maxAllocated == null || peerAllocated > maxAllocated))
                {
                    maxAllocated = peerAllocated;
                }
            }
            allocated = maxAllocated;
        }
        return allocated;
    }

    private Long getDiskAllocated(Map<Volume.Key, Long> vlmAllocatedCapacities, Volume vlm)
        throws AccessDeniedException
    {
        Long allocated;
        Long fetchedAllocated = vlmAllocatedCapacities.get(vlm.getKey());
        if (fetchedAllocated != null)
        {
            allocated = fetchedAllocated;
        }
        else
        {
            StorageDriverKind driverKind = vlm.getStorPool(peerAccCtx.get()).getDriverKind();
            if (driverKind.usesThinProvisioning() || !driverKind.hasBackingStorage())
            {
                allocated = null;
            }
            else
            {
                allocated = vlm.getVolumeDefinition().getVolumeSize(peerAccCtx.get());
            }
        }
        return allocated;
    }

    public static String getVlmDescriptionInline(Volume vlm)
    {
        return getVlmDescriptionInline(vlm.getResource(), vlm.getVolumeDefinition());
    }

    public static String getVlmDescriptionInline(Resource rsc, VolumeDefinition vlmDfn)
    {
        return getVlmDescriptionInline(
            rsc.getAssignedNode().getName().displayValue,
            rsc.getDefinition().getName().displayValue,
            vlmDfn.getVolumeNumber().value
        );
    }

    public static String getVlmDescriptionInline(String nodeNameStr, String rscNameStr, Integer vlmNr)
    {
        return "volume with volume number '" + vlmNr + "' on resource '" + rscNameStr + "' on node '" +
            nodeNameStr + "'";
    }
}