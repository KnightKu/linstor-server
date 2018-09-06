package com.linbit.linstor.storage;

import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ALLOCATED_BYTES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ALLOWABLE_VALUES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_CAPACITY;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_CAPACITY_BYTES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_DATA;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_DURABLE_NAME;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_DURABLE_NAME_FORMAT;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ENDPOINTS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_IDENTIFIERS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_INTEL_RACK_SCALE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_LINKS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ODATA_ID;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_OEM;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_PARAMETERS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_RESOURCE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_VALUE_NQN;
import static com.linbit.linstor.storage.utils.SwordfishConsts.KIB;
import static com.linbit.linstor.storage.utils.SwordfishConsts.PATTERN_NQN;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_ACTIONS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_ATTACH_RESOURCE_ACTION_INFO;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_BASE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_COMPOSED_NODE_ATTACH_RESOURCE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_COMPOSED_NODE_DETACH_RESOURCE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_NODES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_STORAGE_SERVICES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_VOLUMES;

import com.linbit.ChildProcessTimeoutException;
import com.linbit.ImplementationError;
import com.linbit.drbd.md.MaxSizeException;
import com.linbit.drbd.md.MinSizeException;
import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.storage.utils.Crypt;
import com.linbit.linstor.storage.utils.HttpHeader;
import com.linbit.linstor.storage.utils.RestClient;
import com.linbit.linstor.storage.utils.RestClient.RestOp;
import com.linbit.linstor.storage.utils.RestResponse;
import com.linbit.linstor.storage.utils.SwordfishConsts;
import com.linbit.linstor.timer.CoreTimer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;

import com.fasterxml.jackson.jr.ob.impl.MapBuilder;

public class SwordfishInitiatorDriver implements StorageDriver
{
    private final ErrorReporter errorReporter;
    private final SwordfishInitiatorDriverKind swordfishDriverKind;

    private String hostPort;
    private String userName;
    private String userPw;
    private long pollAttachVlmTimeout = 1000;
    private long pollAttachVlmMaxTries = 100;
    private long pollGrepNvmeUuidTimeout = 1000;
    private long pollGrepNvmeUuidMaxTries = 100;
    private String composedNodeName;

    private final RestClient restClient;
    private final Crypt crypt;
    private final CoreTimer timer;

    public SwordfishInitiatorDriver(
        ErrorReporter errorReporterRef,
        SwordfishInitiatorDriverKind swordfishDriverKindRef,
        RestClient restClientRef,
        CoreTimer timerRef,
        Crypt cryptRef
    )
    {
        errorReporter = errorReporterRef;
        swordfishDriverKind = swordfishDriverKindRef;
        restClient = restClientRef;
        timer = timerRef;
        crypt = cryptRef;
    }

    @Override
    public StorageDriverKind getKind()
    {
        return swordfishDriverKind;
    }

    @Override
    public void startVolume(String identifier, String cryptKey, Props vlmDfnProps) throws StorageException
    {
        if (cryptKey != null)
        {
            crypt.openCryptDevice(
                getVolumePath(identifier, false, vlmDfnProps),
                identifier,
                cryptKey.getBytes()
            );
        }
    }

    @Override
    public void stopVolume(String identifier, boolean isEncrypted, Props vlmDfnProps) throws StorageException
    {
        if (isEncrypted)
        {
            crypt.closeCryptDevice(identifier);
        }
    }

    @Override
    public String createVolume(String ignore, long size, String cryptKey, Props vlmDfnProps)
        throws StorageException, MaxSizeException, MinSizeException
    {
        String volumePath;
        try
        {
            String sfStorSvcId = getSfStorSvcId(vlmDfnProps);
            String sfVlmId = getSfVlmId(vlmDfnProps);
            String vlmOdataId = buildVlmOdataId(sfStorSvcId, sfVlmId);

            if (!isSfVolumeAttached(sfStorSvcId, sfVlmId))
            {
                waitUntilSfVlmIsAttachable(vlmOdataId);
                attachSfVolume(vlmOdataId);
            }

            // TODO implement health check on composed node

            volumePath = getVolumePath(ignore, cryptKey != null, vlmDfnProps);
        }
        catch (InterruptedException interruptedExc)
        {
            throw new StorageException("poll timeout interrupted", interruptedExc);
        }
        catch (IOException ioExc)
        {
            throw new StorageException("IO Exception", ioExc);
        }
        return volumePath;
    }

    private void attachSfVolume(String vlmOdataId) throws IOException, StorageException
    {
        String attachAction = SF_BASE + SF_NODES + "/" + composedNodeName + SF_ACTIONS +
            SF_COMPOSED_NODE_ATTACH_RESOURCE;
        RestResponse<Map<String, Object>> attachVlmResp = restClient.execute(
            RestOp.POST,
            hostPort + attachAction,
            getDefaultHeader().build(),
            MapBuilder.defaultImpl().start()
                .put(
                    JSON_KEY_RESOURCE,
                    MapBuilder.defaultImpl().start()
                        .put(JSON_KEY_ODATA_ID, vlmOdataId)
                        .build()
                )
                .build()
        );
        if (attachVlmResp.getStatusCode() != HttpHeader.HTTP_NO_CONTENT)
        {
            throw new StorageException(
                String.format(
                    "Unexpected return code from attaching volume %s: %d",
                    attachAction,
                    attachVlmResp.getStatusCode()
                )
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void waitUntilSfVlmIsAttachable(String vlmOdataId)
        throws InterruptedException, IOException, StorageException
    {
        String attachInfoAction = SF_BASE + SF_NODES + "/" + composedNodeName + SF_ACTIONS +
            SF_ATTACH_RESOURCE_ACTION_INFO;
        boolean attachable = false;

        int pollAttachRscTries = 0;
        while (!attachable)
        {
            errorReporter.logTrace("waiting %d ms before polling node's Actions/AttachResourceActionInfo", pollAttachVlmTimeout);
            Thread.sleep(pollAttachVlmTimeout);

            RestResponse<Map<String, Object>> attachRscInfoResp = restClient.execute(
                RestOp.GET,
                hostPort  + attachInfoAction,
                getDefaultHeader().noContentType().build(),
                (String) null
            );
            if (attachRscInfoResp.getStatusCode() != HttpHeader.HTTP_OK)
            {
                throw new StorageException(
                    String.format(
                        "Unexpected return code (%d) from %s",
                        attachRscInfoResp.getStatusCode(),
                        attachInfoAction
                    )
                );
            }
            Map<String, Object> attachRscInfoData = attachRscInfoResp.getData();
            ArrayList<Object> attachInfoParameters = (ArrayList<Object>) attachRscInfoData.get(JSON_KEY_PARAMETERS);
            for (Object attachInfoParameter : attachInfoParameters)
            {
                Map<String, Object> attachInfoParamMap = (Map<String, Object>) attachInfoParameter;
                ArrayList<Object> paramAllowableValues = (ArrayList<Object>) attachInfoParamMap.get(JSON_KEY_ALLOWABLE_VALUES);
                if (paramAllowableValues != null)
                {
                    for (Object paramAllowableValue : paramAllowableValues)
                    {
                        if (paramAllowableValue instanceof Map)
                        {
                            Map<String, Object> paramAllowableValueMap = (Map<String, Object>) paramAllowableValue;
                            String attachableVlmId = (String) paramAllowableValueMap.get(JSON_KEY_ODATA_ID);
                            if (vlmOdataId.equals(attachableVlmId))
                            {
                                attachable = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (pollAttachRscTries++ >= pollAttachVlmMaxTries)
            {
                throw new StorageException(
                    String.format(
                        "Volume could not be attached after %d x %dms. \n" +
                        "Volume did not show up in %s -> %s from GET %s",
                        pollAttachRscTries,
                        pollAttachVlmTimeout,
                        JSON_KEY_PARAMETERS,
                        JSON_KEY_ALLOWABLE_VALUES,
                        hostPort  + attachInfoAction
                    )
                );
            }
        }
    }

    @Override
    public void deleteVolume(String ignore, boolean isEncrypted, Props vlmDfnProps) throws StorageException
    {
        try
        {
            detatchSfVlm(vlmDfnProps);

            // TODO health check on composed node
        }
        catch (IOException ioExc)
        {
            throw new StorageException("IO Exception", ioExc);
        }
    }

    private String detatchSfVlm(Props vlmDfnProps) throws IOException, StorageException
    {
        String sfStorSvcId = getSfStorSvcId(vlmDfnProps);
        String vlmOdataId = buildVlmOdataId(sfStorSvcId, getSfVlmId(vlmDfnProps));

        // detach volume from node
        String detachAction = SF_BASE + SF_NODES + "/" + composedNodeName + SF_ACTIONS +
            SF_COMPOSED_NODE_DETACH_RESOURCE;
        // POST to Node/$id/Action/ComposedNode.DetachResource
        RestResponse<Map<String, Object>> detachVlmResp = restClient.execute(
            RestOp.POST,
            hostPort + detachAction,
            getDefaultHeader().build(),
            MapBuilder.defaultImpl().start()
            .put(
                JSON_KEY_RESOURCE,
                MapBuilder.defaultImpl().start()
                    .put(JSON_KEY_ODATA_ID, vlmOdataId)
                    .build()
            )
            .build()
        );
        // detatchVlmResp.getStatusCode() might be 404 (not found) which is fine, just continue
        // with deletion of the volume
        return vlmOdataId;
    }

    @Override
    public boolean volumeExists(String ignore, boolean isEncrypted, Props vlmDfnProps) throws StorageException
    {
        boolean exists = false;

        // TODO implement encrypted "volumesExists"
        String sfStorSvcId = getSfStorSvcId(vlmDfnProps);
        String sfVlmId = getSfVlmId(vlmDfnProps);
        exists = sfVolumeExists(sfStorSvcId, sfVlmId) && isSfVolumeAttached(sfStorSvcId, sfVlmId);
        return exists;
    }

    private String getSfVlmId(Props vlmDfnProps) throws StorageException
    {
        return getSfProp(
            vlmDfnProps,
            SwordfishConsts.DRIVER_SF_VLM_ID_KEY,
            "volume id"
        );
    }

    private String getSfStorSvcId(Props vlmDfnProps) throws StorageException
    {
        return getSfProp(
            vlmDfnProps,
            SwordfishConsts.DRIVER_SF_STOR_SVC_ID_KEY,
            "storage service id"
        );
    }

    private String getSfProp(Props vlmDfnProps, String propKey, String errObjDescr)
        throws StorageException, ImplementationError
    {
        try
        {
            String sfVlmId = vlmDfnProps.getProp(
                propKey,
                StorageDriver.STORAGE_NAMESPACE
            );
            if (sfVlmId == null)
            {
                throw new StorageException(
                    "No swordfish " + errObjDescr + " given. This usually happens if you forgot to " +
                        "create a resource of this resource definition using a swordfishTargetDriver"
                );
            }

            return sfVlmId;
        }
        catch (InvalidKeyException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    private boolean sfVolumeExists(String sfStorSvcId, String sfVlmId) throws StorageException
    {
        return getSfVlm(sfStorSvcId, sfVlmId).getStatusCode() == HttpHeader.HTTP_OK;
    }

    @SuppressWarnings("unchecked")
    private boolean isSfVolumeAttached(String sfStorSvcId, String sfVlmId) throws StorageException
    {
        boolean attached = false;
        try
        {
            String vlmOdataId = buildVlmOdataId(sfStorSvcId, sfVlmId);

            String composedNodeAttachAction =
                SF_BASE + SF_NODES + composedNodeName + SF_ACTIONS + SF_ATTACH_RESOURCE_ACTION_INFO;
            RestResponse<Map<String, Object>> attachInfoResp = restClient.execute(
                RestOp.GET,
                hostPort + composedNodeAttachAction,
                getDefaultHeader().noContentType().build(),
                (String) null
            );
            if (attachInfoResp.getStatusCode() == HttpHeader.HTTP_OK)
            {
                Map<String, Object> attachInfoData = attachInfoResp.getData();
                Map<String, Object> paramMap = (Map<String, Object>) attachInfoData.get(JSON_KEY_PARAMETERS);
                Object[] allowableValues = (Object[]) paramMap.get(JSON_KEY_ALLOWABLE_VALUES);

                for (Object allowableValue : allowableValues)
                {
                    if (vlmOdataId.equals(
                        ((Map<String, Object>)allowableValue).get(JSON_KEY_ODATA_ID))
                    )
                    {
                        attached = true;
                        break;
                    }
                }
            }
        }
        catch (IOException exc)
        {
            throw new StorageException("IO exception", exc);
        }
        catch (ClassCastException ccExc)
        {
            throw new StorageException("Unexpected datastucture", ccExc);
        }
        return attached;
    }

    @Override
    public SizeComparison compareVolumeSize(String lsIdentifier, long requiredSize, Props vlmDfnProps) throws StorageException
    {
        SizeComparison ret;

        String sfStorSvcId = getSfStorSvcId(vlmDfnProps);
        String sfVlmId = getSfVlmId(vlmDfnProps);
        RestResponse<Map<String, Object>> response = getSfVlm(sfStorSvcId, sfVlmId);

        if (response.getStatusCode() == HttpHeader.HTTP_OK)
        {
            long actualSize = getLong(response.getData().get(JSON_KEY_CAPACITY_BYTES)) / KIB;
            if (actualSize >= requiredSize)
            {
                ret = SizeComparison.WITHIN_TOLERANCE; // no upper bound (yet)... TODO
            }
            else
            {
                ret = SizeComparison.TOO_SMALL;
            }
        }
        else
        {
            throw new StorageException(
                "Could not determine size of swordfish volume: '" + lsIdentifier + "'\n" +
                "GET returned status code: " + response.getStatusCode()
            );
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    @Override
    public String getVolumePath(String linstorVlmId, boolean isEncrypted, Props vlmDfnProps) throws StorageException
    {
        String path = null;
        if (isEncrypted)
        {
            path = crypt.getCryptVolumePath(linstorVlmId);
        }
        else
        {
            try
            {
                String nqnUuid = null;

                String sfStorSvcId = getSfStorSvcId(vlmDfnProps);
                String sfVlmId = getSfVlmId(vlmDfnProps);
                RestResponse<Map<String, Object>> vlmInfo = getSfVlm(sfStorSvcId, sfVlmId);

                Map<String, Object> vlmData = vlmInfo.getData();
                Map<String, Object> vlmLinks = (Map<String, Object>) vlmData.get(JSON_KEY_LINKS);
                Map<String, Object> linksOem = (Map<String, Object>) vlmLinks.get(JSON_KEY_OEM);
                Map<String, Object> oemIntelRackscale = (Map<String, Object>) linksOem.get(JSON_KEY_INTEL_RACK_SCALE);
                ArrayList<Object> intelRackscaleEndpoints = (ArrayList<Object>) oemIntelRackscale.get(JSON_KEY_ENDPOINTS);
                Map<String, Object> endpoint = (Map<String, Object>) intelRackscaleEndpoints.get(0);
                String endpointOdataId = (String) endpoint.get(JSON_KEY_ODATA_ID);

                RestResponse<Map<String, Object>> endpointInfo = getSwordfishResource(endpointOdataId);
                Map<String, Object> endpointData = endpointInfo.getData();
                ArrayList<Object> endpointIdentifiers = (ArrayList<Object>) endpointData.get(JSON_KEY_IDENTIFIERS);
                for (Object endpointIdentifier : endpointIdentifiers)
                {
                    Map<String, Object> endpointIdMap = (Map<String, Object>) endpointIdentifier;
                    if (JSON_VALUE_NQN.equals(endpointIdMap.get(JSON_KEY_DURABLE_NAME_FORMAT)))
                    {
                        String nqn = (String) endpointIdMap.get(JSON_KEY_DURABLE_NAME);
                        nqnUuid = nqn.substring(nqn.lastIndexOf("uuid:") + "uuid:".length());

                        int grepTries = 0;
                        boolean grepFailed = false;
                        boolean grepFound = false;
                        while (!grepFailed && !grepFound)
                        {
                            final ExtCmd extCmd = new ExtCmd(timer, errorReporter);
                            OutputData outputData = extCmd.exec(
                                "/bin/bash",
                                "-c",
                                "grep -H --color=never " + nqnUuid + " /sys/devices/virtual/nvme-fabrics/ctl/nvme*/subsysnqn"
                            );
                            if (outputData.exitCode == 0)
                            {
                                String outString = new String(outputData.stdoutData);
                                Matcher matcher = PATTERN_NQN.matcher(outString);
                                if (matcher.find())
                                {
                                    // although nvme supports multiple namespace, the current implementation
                                    // relies on podmanager's limitation of only supporting one namespace per
                                    // nvme device
                                    path = "/dev/nvme" + matcher.group(1) + "n1";
                                    grepFound = true;
                                }
                            }

                            if (grepTries++ >= pollGrepNvmeUuidMaxTries)
                            {
                                grepFailed = true;
                            }
                            else
                            {
                                Thread.sleep(pollGrepNvmeUuidTimeout);
                            }
                        }
                        break;
                    }
                }
                if (path == null)
                {
                    throw new StorageException("Could not extract system path of volume");
                }
            }
            catch (ClassCastException ccExc)
            {
                throw new StorageException("Unexpected json structure", ccExc);
            }
            catch (ChildProcessTimeoutException timeoutExc)
            {
                throw new StorageException("External operation timed out", timeoutExc);
            }
            catch (InterruptedException interruptedExc)
            {
                throw new StorageException("Interrupted exception", interruptedExc);
            }
            catch (IOException ioExc)
            {
                throw new StorageException("IO exception occured", ioExc);
            }

            // TODO: somehow we have to find out the NVME device. for that we need at least
            // the target NQN maybe via the volume?
            // if we have the NQN we can make the following external calls:

            // ext command on initiator from folder
            // /sys/devices/virtual/nvme-fabrics/ctl
            // cmd: grep -H --color=never <target NQN>  nvme*/subsysnqn
            // nvme2/subsysnqn:<target NQN>                 # this is how we get the nvme$number
            // the previous command should also have exitcode 0

//            throw new ImplementationError("Not implemented yet");
        }
        return path;
    }

    @Override
    public long getSize(String ignore, Props vlmDfnProps) throws StorageException
    {
        return getSpace(
            getSfVlm(getSfStorSvcId(vlmDfnProps), getSfVlmId(vlmDfnProps)),
            JSON_KEY_ALLOCATED_BYTES
        );
    }

    @Override
    public long getFreeSpace() throws StorageException
    {
        return Long.MAX_VALUE;
//        return getSpace(
//            getSwordfishPool(),
//            JSON_KEY_GUARANTEED_BYTES
//        );
    }

    @Override
    public long getTotalSpace() throws StorageException
    {
        return Long.MAX_VALUE;
//        return getSpace(
//            getSwordfishPool(),
//            JSON_KEY_ALLOCATED_BYTES
//        );
    }

    @SuppressWarnings("unchecked")
    private long getSpace(RestResponse<Map<String, Object>> restResponse, String key) throws StorageException
    {
        Map<String, Object> poolData = restResponse.getData();
        Long space = null;
        try
        {
            Map<String, Object> capacity = (Map<String, Object>) poolData.get(JSON_KEY_CAPACITY);
            Map<String, Object> capacityData = (Map<String, Object>) capacity.get(JSON_KEY_DATA);
            Object spaceObj = capacityData.get(key);
            if (spaceObj instanceof Integer)
            {
                space = ((Integer)spaceObj).longValue();
            }
            else
            if (spaceObj instanceof Long)
            {
                space = (Long) spaceObj;
            }
        }
        catch (ClassCastException ccExc)
        {
            throw new StorageException("Unexpected json structure in response", ccExc);
        }
        catch (NullPointerException npExc)
        {
            throw new StorageException("Unexpected null entry in response", npExc);
        }
        if (space == null)
        {
            throw new StorageException("Could not retrieve requested space");
        }
        // linstor uses kb as internal unit, swordfish gives us bytes
        return space / KIB;

    }

    @Override
    public Map<String, String> getTraits(String ignore) throws StorageException
    {
        return Collections.emptyMap();
    }

    @Override
    public void setConfiguration(
        String storPoolNameStr,
        Map<String, String> storPoolNamespace,
        Map<String, String> nodeNamespace,
        Map<String, String> stltNamespace
    )
        throws StorageException
    {
        // first, check if the config is valid
        boolean requiresHostPort = hostPort == null;
        boolean requiresComposedNodeName = composedNodeName == null;

        String tmpHostPort = stltNamespace.get(StorageConstants.CONFIG_SF_URL_KEY);
        String tmpUserName = stltNamespace.get(StorageConstants.CONFIG_SF_USER_NAME_KEY);
        String tmpUserPw = stltNamespace.get(StorageConstants.CONFIG_SF_USER_PW_KEY);
        String tmpComposedNodeName = nodeNamespace.get(StorageConstants.CONFIG_SF_COMPOSED_NODE_NAME_KEY);
        String tmpAttachVlmTimeout = storPoolNamespace.get(StorageConstants.CONFIG_SF_POLL_TIMEOUT_ATTACH_VLM_KEY);
        String tmpAttachVlmRetries = storPoolNamespace.get(StorageConstants.CONFIG_SF_POLL_RETRIES_ATTACH_VLM_KEY);
        String tmpGrepNvmeUuidTimeout = storPoolNamespace.get(StorageConstants.CONFIG_SF_POLL_TIMEOUT_GREP_NVME_UUID_KEY);
        String tmpGrepNvmeUuidRetries = storPoolNamespace.get(StorageConstants.CONFIG_SF_POLL_RETRIES_GREP_NVME_UUID_KEY);

        // temporary workaround to not having to disable security to set this property on controller-level
        if (tmpHostPort == null || tmpHostPort.isEmpty())
        {
            tmpHostPort = nodeNamespace.get(StorageConstants.CONFIG_SF_URL_KEY);
        }
        if (tmpUserName == null || tmpUserName.isEmpty())
        {
            tmpUserName = nodeNamespace.get(StorageConstants.CONFIG_SF_USER_NAME_KEY);
        }
        if (tmpUserPw == null || tmpUserPw.isEmpty())
        {
            tmpUserPw = nodeNamespace.get(StorageConstants.CONFIG_SF_USER_PW_KEY);
        }

        StringBuilder failErrorMsg = new StringBuilder();
        appendIfEmptyButRequired(
                "Missing swordfish host:port specification as a single value such as \n" +
                    "https://127.0.0.1:1234\n"+
                    "This property has to be set globally:\n\n" +
                    "linstor controller set-property " +
                    ApiConsts.NAMESPC_STORAGE_DRIVER + "/" + StorageConstants.CONFIG_SF_URL_KEY +
                    " <value>\n",
                failErrorMsg,
                tmpHostPort,
                requiresHostPort
        );
        appendIfEmptyButRequired(
            "Missing swordfish composed node name\n" +
                "This property has to be set on node level: \n\n" +
                "linstor node set-property <node_name> " +
                ApiConsts.NAMESPC_STORAGE_DRIVER + "/" + StorageConstants.CONFIG_SF_COMPOSED_NODE_NAME_KEY +
                " <composed_node_id>",
            failErrorMsg,
            tmpComposedNodeName,
            requiresComposedNodeName
        );
        Long tmpAttachVlmTimeoutLong = getLong("poll attach volume timeout", failErrorMsg, tmpAttachVlmTimeout);
        Long tmpAttachVlmTriesLong = getLong("poll attach volume tries", failErrorMsg, tmpAttachVlmRetries);
        Long tmpGrepNvmeUuidTimeoutLong = getLong("poll grep nvme uuid timeout", failErrorMsg, tmpGrepNvmeUuidTimeout);
        Long tmpGrepNvmeUuidTriesLong = getLong("poll grep nvme uuid tries", failErrorMsg, tmpGrepNvmeUuidRetries);

        //TODO: perform a check if this compute node is really the confugired composed node
        // i.e. `uname -n` maches /redfish/v1/Nodes/<composedNodeId> -> "Name"

        if (!failErrorMsg.toString().trim().isEmpty())
        {
            throw new StorageException(failErrorMsg.toString());
        }

        // if all was good, apply the values
        if (tmpHostPort != null)
        {
            while (tmpHostPort.endsWith("/"))
            {
                tmpHostPort = tmpHostPort.substring(0, tmpHostPort.length() - 1); // cut the trailing '/'
            }
            if (!tmpHostPort.startsWith("http"))
            {
                tmpHostPort = "http://" + tmpHostPort;
            }
            if (tmpHostPort.endsWith("/redfish/v1"))
            {
                tmpHostPort = tmpHostPort.substring(0, tmpHostPort.lastIndexOf("/redfish/v1"));
            }
            hostPort = tmpHostPort + "/";
        }
        if (tmpUserName != null)
        {
            userName = tmpUserName;
        }
        if (tmpUserPw != null)
        {
            userName = tmpUserPw;
        }
        if (tmpAttachVlmTimeoutLong != null)
        {
            pollAttachVlmTimeout = tmpAttachVlmTimeoutLong;
        }
        if (tmpAttachVlmTriesLong != null)
        {
            pollAttachVlmMaxTries = tmpAttachVlmTriesLong;
        }
        if (tmpGrepNvmeUuidTimeoutLong != null)
        {
            pollGrepNvmeUuidTimeout = tmpGrepNvmeUuidTimeoutLong;
        }
        if (tmpGrepNvmeUuidTriesLong != null)
        {
            pollGrepNvmeUuidMaxTries = tmpGrepNvmeUuidTriesLong;
        }
        if (tmpComposedNodeName != null)
        {
            composedNodeName = tmpComposedNodeName;
        }
    }

    private void appendIfEmptyButRequired(String errorMsg, StringBuilder errorMsgBuilder, String str, boolean reqStr)
    {
        if ((str == null || str.isEmpty()) && reqStr)
        {
            errorMsgBuilder.append(errorMsg);
        }
    }

    private Long getLong(String description, StringBuilder failErrorMsg, String str)
    {
        Long ret = null;
        if (str != null && !str.isEmpty())
        {
            try
            {
                ret = Long.parseLong(str);
            }
            catch (NumberFormatException nfe)
            {
                failErrorMsg
                    .append("Configured ")
                    .append(description)
                    .append(" value (")
                    .append(str)
                    .append(") is not a number\n");
            }
        }
        return ret;
    }

    @Override
    public void resizeVolume(String ignore, long size, String cryptKey, Props vlmDfnProps)
        throws StorageException, MaxSizeException, MinSizeException
    {
        throw new ImplementationError("Resizing swordfish volumes is not supported");
    }

    @Override
    public void createSnapshot(String ignore, String snapshotName) throws StorageException
    {
        throw new StorageException("Swordfish driver cannot create snapshots");
    }

    @Override
    public void restoreSnapshot(
        String sourceIdentifier,
        String snapshotName,
        String targetIdentifier,
        String cryptKey,
        Props vlmDfnProps
    )
        throws StorageException
    {
        throw new StorageException("Swordfish driver cannot create or restore snapshots");
    }

    @Override
    public void deleteSnapshot(String identifier, String snapshotName) throws StorageException
    {
        throw new StorageException("Swordfish driver cannot create or delete snapshots");
    }

    @Override
    public boolean snapshotExists(String volumeIdentifier, String snapshotName) throws StorageException
    {
        throw new ImplementationError("Snapshots of swordfish volumes are not supported");
    }

//    private RestResponse<Map<String, Object>> getSwordfishPool(String sfStorSvcId, String sfStorPoolId)
//        throws StorageException
//    {
//        return getSwordfishResource(
//            SF_BASE + SF_STORAGE_SERVICES + "/" + sfStorSvcId + SF_STORAGE_POOLS + "/" + sfStorPoolId
//        );
//    }

    private RestResponse<Map<String, Object>> getSfVlm(String sfStorSvcId, String sfVlmId)
        throws StorageException
    {
        return getSwordfishResource(buildVlmOdataId(sfStorSvcId, sfVlmId));
    }

    private String buildVlmOdataId(String sfStorSvcId, String sfVlmId)
    {
        return SF_BASE + SF_STORAGE_SERVICES + "/" + sfStorSvcId + SF_VOLUMES + "/" + sfVlmId;
    }

    private RestResponse<Map<String, Object>> getSwordfishResource(String odataId)
        throws StorageException
    {
        RestResponse<Map<String, Object>> rscInfo;
        try
        {
            rscInfo = restClient.execute(
                RestOp.GET,
                hostPort + odataId,
                getDefaultHeader().build(),
                (String) null
            );

            if (rscInfo.getStatusCode() != HttpHeader.HTTP_OK)
            {
                throw new StorageException(
                    "Error receiving info of swordfish resource '" + odataId + "'. Status code: " +
                    rscInfo.getStatusCode()
                );
            }
        }
        catch (IOException ioExc)
        {
            throw new StorageException("IO Exception", ioExc);
        }
        return rscInfo;
    }

    private HttpHeader.Builder getDefaultHeader()
    {
        HttpHeader.Builder httpHeaderBuilder = HttpHeader.newBuilder();
        httpHeaderBuilder.setJsonContentType();
        if (userName != null && !userName.isEmpty())
        {
            httpHeaderBuilder.setAuth(userName, userPw);
        }
        return httpHeaderBuilder;
    }

    private long getLong(Object object)
    {
        long ret;
        if (object instanceof Integer)
        {
            ret = ((Integer) object).longValue();
        }
        else
        {
            ret = (long) object;
        }
        return ret;
    }
}