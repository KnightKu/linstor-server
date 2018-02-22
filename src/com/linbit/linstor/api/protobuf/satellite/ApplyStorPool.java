package com.linbit.linstor.api.protobuf.satellite;

import com.google.inject.Inject;

import com.linbit.ImplementationError;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.api.ApiCall;
import com.linbit.linstor.api.pojo.StorPoolPojo;
import com.linbit.linstor.api.protobuf.ApiCallAnswerer;
import com.linbit.linstor.api.protobuf.ProtoMapUtils;
import com.linbit.linstor.api.protobuf.ProtobufApiCall;
import com.linbit.linstor.core.ControllerPeerConnector;
import com.linbit.linstor.core.StltApiCallHandler;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.proto.StorPoolFreeSpaceOuterClass.StorPoolFreeSpace;
import com.linbit.linstor.proto.javainternal.MsgIntApplyStorPoolSuccessOuterClass.MsgIntApplyStorPoolSuccess;
import com.linbit.linstor.proto.javainternal.MsgIntStorPoolDataOuterClass.MsgIntStorPoolData;
import com.linbit.linstor.storage.StorageException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

@ProtobufApiCall(
    name = InternalApiConsts.API_APPLY_STOR_POOL,
    description = "Applies storage pool update data"
)
public class ApplyStorPool implements ApiCall
{
    private final StltApiCallHandler apiCallHandler;
    private final ApiCallAnswerer apiCallAnswerer;
    private final ControllerPeerConnector controllerPeerConnector;
    private final Peer controllerPeer;
    private final ErrorReporter errorReporter;

    @Inject
    public ApplyStorPool(
        StltApiCallHandler apiCallHandlerRef,
        ApiCallAnswerer apiCallAnswererRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        Peer controllerPeerRef,
        ErrorReporter errorReporterRef
    )
    {
        apiCallHandler = apiCallHandlerRef;
        apiCallAnswerer = apiCallAnswererRef;
        controllerPeerConnector = controllerPeerConnectorRef;
        controllerPeer = controllerPeerRef;
        errorReporter = errorReporterRef;
    }

    @Override
    public void execute(InputStream msgDataIn)
        throws IOException
    {
        MsgIntStorPoolData storPoolData = MsgIntStorPoolData.parseDelimitedFrom(msgDataIn);

        StorPoolPojo storPoolRaw = asStorPoolPojo(
            storPoolData,
            controllerPeerConnector.getLocalNode().getName().displayValue
        );
        apiCallHandler.applyStorPoolChanges(storPoolRaw);

        try
        {
            Map<StorPool, Long> freeSpaceMap = apiCallHandler.getFreeSpace();
            System.out.println(freeSpaceMap);
            System.out.println(storPoolData.getStorPoolName());

            Long requestedFreeSpace = null;

            String storPoolName = storPoolData.getStorPoolName().toUpperCase();
            for (Entry<StorPool, Long> entry : freeSpaceMap.entrySet())
            {
                if (entry.getKey().getName().value.equals(storPoolName))
                {
                    requestedFreeSpace = entry.getValue();
                    break;
                }
            }

            if (requestedFreeSpace == null)
            {
                errorReporter.reportError(
                    new ImplementationError(
                        "Applied storage pool name not found",
                        null
                    )
                );
            }
            else
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                MsgIntApplyStorPoolSuccess.newBuilder()
                    .setFreeSpace(StorPoolFreeSpace.newBuilder()
                        .setStorPoolUuid(storPoolData.getStorPoolUuid())
                        .setStorPoolName(storPoolData.getStorPoolName())
                        .setFreeSpace(requestedFreeSpace)
                        .build()
                    )
                    .build()
                    .writeDelimitedTo(baos);
                controllerPeer.sendMessage(
                    apiCallAnswerer.prepareMessage(
                        baos.toByteArray(),
                        InternalApiConsts.API_APPLY_STOR_POOL_SUCCESS
                    )
                );
            }
        }
        catch (StorageException storageExc)
        {
            // TODO: report about this error to the controller
            errorReporter.reportError(storageExc);
        }
    }

    static StorPoolPojo asStorPoolPojo(MsgIntStorPoolData storPoolData, String nodeName)
    {
        StorPoolPojo storPoolRaw = new StorPoolPojo(
            UUID.fromString(storPoolData.getStorPoolUuid()),
            UUID.fromString(storPoolData.getNodeUuid()),
            nodeName,
            storPoolData.getStorPoolName(),
            UUID.fromString(storPoolData.getStorPoolDfnUuid()),
            storPoolData.getDriver(),
            ProtoMapUtils.asMap(storPoolData.getStorPoolPropsList()),
            ProtoMapUtils.asMap(storPoolData.getStorPoolDfnPropsList()),
            null,
            Collections.<String, String>emptyMap(),
            storPoolData.getFullSyncId(),
            storPoolData.getUpdateId()
        );
        return storPoolRaw;
    }

}
