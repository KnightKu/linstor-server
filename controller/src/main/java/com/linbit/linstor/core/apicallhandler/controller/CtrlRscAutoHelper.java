package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.core.apicallhandler.controller.internal.CtrlSatelliteUpdateCaller;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiDatabaseException;
import com.linbit.linstor.core.apicallhandler.response.CtrlResponseUtils;
import com.linbit.linstor.core.apicallhandler.response.ResponseContext;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.Resource.Flags;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.StateFlags;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import reactor.core.publisher.Flux;

@Singleton
public class CtrlRscAutoHelper
{
    private final CtrlApiDataLoader dataLoader;
    private final CtrlRscAutoQuorumHelper autoQuorumHelper;
    private final CtrlRscAutoTieBreakerHelper autoTieBreakerHelper;
    private final Provider<AccessContext> peerAccCtx;
    private final CtrlRscCrtApiHelper rscCrtHelper;
    private final CtrlRscDeleteApiHelper rscDelHelper;
    private final CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCaller;

    public static class AutoHelperResult
    {
        private Flux<ApiCallRc> flux;
        private boolean preventUpdateSatellitesForResourceDelete;

        private AutoHelperResult()
        {
        }

        public Flux<ApiCallRc> getFlux()
        {
            return flux;
        }

        public boolean isPreventUpdateSatellitesForResourceDelete()
        {
            return preventUpdateSatellitesForResourceDelete;
        }
    }

    @Inject
    public CtrlRscAutoHelper(
        CtrlRscAutoQuorumHelper autoQuorumHelperRef,
        CtrlRscAutoTieBreakerHelper autoTieBreakerRef,
        CtrlApiDataLoader dataLoaderRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        CtrlRscCrtApiHelper rscCrtHelperRef,
        CtrlRscDeleteApiHelper rscDelHelperRef,
        CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCallerRef
    )
    {
        autoQuorumHelper = autoQuorumHelperRef;
        autoTieBreakerHelper = autoTieBreakerRef;

        dataLoader = dataLoaderRef;
        peerAccCtx = peerAccCtxRef;
        rscCrtHelper = rscCrtHelperRef;
        rscDelHelper = rscDelHelperRef;
        ctrlSatelliteUpdateCaller = ctrlSatelliteUpdateCallerRef;
    }

    public AutoHelperResult manage(ApiCallRcImpl apiCallRcImplRef, ResponseContext context, String rscNameStrRef)
    {
        return manage(apiCallRcImplRef, context, dataLoader.loadRscDfn(rscNameStrRef, true));
    }

    public AutoHelperResult manage(
        ApiCallRcImpl apiCallRcImpl,
        ResponseContext context,
        ResourceDefinition rscDfn
    )
    {
        AutoHelperResult result = new AutoHelperResult();
        AutoHelperInternalState autoHelperInternalState = new AutoHelperInternalState();

        autoTieBreakerHelper.manage(
            apiCallRcImpl,
            rscDfn,
            autoHelperInternalState
        );
        autoQuorumHelper.manage(apiCallRcImpl, rscDfn);

        if (!autoHelperInternalState.resourcesToCreate.isEmpty())
        {
            autoHelperInternalState.additionalFluxList.add(
                rscCrtHelper.deployResources(
                    context,
                    autoHelperInternalState.resourcesToCreate
                )
            );
            autoHelperInternalState.fluxUpdateApplied = true;
        }

        if (!autoHelperInternalState.nodeNamesForDelete.isEmpty())
        {
            autoHelperInternalState.additionalFluxList.add(
                rscDelHelper.updateSatellitesForResourceDelete(
                    autoHelperInternalState.nodeNamesForDelete,
                    rscDfn.getName()
                )
            );
            autoHelperInternalState.fluxUpdateApplied = true;
        }

        if (autoHelperInternalState.requiresUpdateFlux && !autoHelperInternalState.fluxUpdateApplied)
        {
            autoHelperInternalState.additionalFluxList.add(
                ctrlSatelliteUpdateCaller.updateSatellites(rscDfn, Flux.empty())
                    .transform(
                        updateResponses -> CtrlResponseUtils.combineResponses(
                            updateResponses,
                            rscDfn.getName(),
                            "Resource {1} updated on node {0}"
                        )
                    )
            );
        }

        result.flux = Flux.merge(autoHelperInternalState.additionalFluxList);
        result.preventUpdateSatellitesForResourceDelete = autoHelperInternalState.preventUpdateSatellitesForResourceDelete;
        return result;
    }

    public Resource getTiebreakerResource(String nodeNameRef, String nameRef)
    {
        Resource ret = null;
        Resource rsc = dataLoader.loadRsc(nodeNameRef, nameRef, false);
        try
        {
            if (rsc != null && rsc.getStateFlags().isSet(peerAccCtx.get(), Resource.Flags.TIE_BREAKER))
            {
                ret = rsc;
            }
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "check if given resource is a tiebreaker",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        return ret;
    }

    public void removeTiebreakerFlag(Resource tiebreakerRef)
    {
        try
        {
            StateFlags<Flags> flags = tiebreakerRef.getStateFlags();
            flags.disableFlags(peerAccCtx.get(), Resource.Flags.TIE_BREAKER);
            flags.enableFlags(peerAccCtx.get(), Resource.Flags.DRBD_DISKLESS);
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "remove tiebreaker flag from resource",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (DatabaseException exc)
        {
            throw new ApiDatabaseException(exc);
        }
    }

    static class AutoHelperInternalState
    {
        TreeSet<Resource> resourcesToCreate = new TreeSet<>();
        TreeSet<NodeName> nodeNamesForDelete = new TreeSet<>();

        List<Flux<ApiCallRc>> additionalFluxList = new ArrayList<>();

        boolean requiresUpdateFlux = false;
        boolean fluxUpdateApplied = false;

        boolean preventUpdateSatellitesForResourceDelete = false;

        private AutoHelperInternalState()
        {

        }
    }
}