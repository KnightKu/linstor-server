package com.linbit.linstor.storage.data.adapter.cache;

import com.linbit.linstor.api.pojo.CacheRscPojo.CacheVlmPojo;
import com.linbit.linstor.core.objects.AbsResource;
import com.linbit.linstor.core.objects.AbsVolume;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.interfaces.CacheLayerDatabaseDriver;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.data.AbsVlmData;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmDfnLayerObject;
import com.linbit.linstor.storage.interfaces.layers.State;
import com.linbit.linstor.storage.interfaces.layers.cache.CacheVlmObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.linstor.transaction.manager.TransactionMgr;

import javax.annotation.Nullable;
import javax.inject.Provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CacheVlmData<RSC extends AbsResource<RSC>>
    extends AbsVlmData<RSC, CacheRscData<RSC>>
    implements CacheVlmObject<RSC>
{
    // unmodifiable data, once initialized
    private final StorPool metaStorPool;
    private final StorPool cacheStorPool;

    // not persisted, serialized, ctrl and stlt
    private long allocatedSize = UNINITIALIZED_SIZE;
    private long usableSize = UNINITIALIZED_SIZE;
    private @Nullable String devicePathData;
    private @Nullable String devicePathCache;
    private @Nullable String devicePathMeta;
    private String backingDevice;
    private String diskState;

    // not persisted, not serialized, stlt only
    private boolean exists;
    private boolean failed;
    private boolean opened;
    private String identifier;
    private List<? extends State> unmodStates;
    private Size sizeState;
    private long originalSize = UNINITIALIZED_SIZE;

    public CacheVlmData(
        AbsVolume<RSC> vlmRef,
        CacheRscData<RSC> rscDataRef,
        @Nullable StorPool cacheStorPoolRef,
        @Nullable StorPool metaStorPoolRef,
        CacheLayerDatabaseDriver dbDriver,
        TransactionObjectFactory transObjFactory,
        Provider<? extends TransactionMgr> transMgrProvider
    )
    {
        super(vlmRef, rscDataRef, transMgrProvider);
        cacheStorPool = cacheStorPoolRef; // might be null for peer nodes
        metaStorPool = metaStorPoolRef; // might be null for peer nodes

        unmodStates = Collections.emptyList();

        transObjs = new ArrayList<>();
        transObjs.add(vlm);
        transObjs.add(rscData);
        if (cacheStorPool != null)
        {
            transObjs.add(cacheStorPool);
        }
    }

    @Override
    public boolean exists()
    {
        return exists;
    }

    public void setExists(boolean existsRef)
    {
        exists = existsRef;
    }

    @Override
    public boolean hasFailed()
    {
        return failed;
    }

    @Override
    public long getOriginalSize()
    {
        return originalSize;
    }

    @Override
    public void setOriginalSize(long originalSizeRef)
    {
        originalSize = originalSizeRef;
    }

    @Override
    public long getAllocatedSize()
    {
        return allocatedSize;
    }

    @Override
    public void setAllocatedSize(long allocatedSizeRef)
    {
        allocatedSize = allocatedSizeRef;
    }

    @Override
    public VlmDfnLayerObject getVlmDfnLayerObject()
    {
        return null;
    }

    @Override
    public void setUsableSize(long usableSizeRef) throws DatabaseException
    {
        if (usableSizeRef != usableSize)
        {
            if (usableSize < usableSizeRef)
            {
                sizeState = Size.TOO_SMALL;
            }
            else
            {
                sizeState = Size.TOO_LARGE;
            }
        }
        else
        {
            sizeState = Size.AS_EXPECTED;
        }
        usableSize = usableSizeRef;
    }

    @Override
    public long getUsableSize()
    {
        return usableSize;
    }

    @Override
    public String getDevicePath()
    {
        return devicePathData;
    }

    public void setDevicePath(String devicePathRef)
    {
        devicePathData = devicePathRef;
    }

    public String getBackingDevicePath()
    {
        return backingDevice;
    }

    public void setBackingDevice(String backingDeviceRef)
    {
        backingDevice = backingDeviceRef;
    }

    public String getCacheDevicePath()
    {
        return devicePathCache;
    }

    public void setCacheDevice(String devicePathMetaRef)
    {
        devicePathCache = devicePathMetaRef;
    }

    public String getMetaDevicePath()
    {
        return devicePathMeta;
    }

    public void setMetaDevice(String devicePathMetaRef)
    {
        devicePathMeta = devicePathMetaRef;
    }

    @Override
    public Size getSizeState()
    {
        return sizeState;
    }

    @Override
    public List<? extends State> getStates()
    {
        return unmodStates;
    }

    @Override
    public String getIdentifier()
    {
        return identifier;
    }

    public void setIdentifier(String identifierRef)
    {
        identifier = identifierRef;
    }

    public String getDiskState()
    {
        return diskState;
    }

    public void setDiskState(String diskStateRef)
    {
        diskState = diskStateRef;
    }

    public StorPool getCacheStorPool()
    {
        return cacheStorPool;
    }

    public StorPool getMetaStorPool()
    {
        return metaStorPool;
    }

    @Override
    public CacheVlmPojo asPojo(AccessContext accCtxRef) throws AccessDeniedException
    {
        return new CacheVlmPojo(
            getVlmNr().value,
            devicePathData,
            devicePathCache,
            devicePathMeta,
            cacheStorPool == null ? null : cacheStorPool.getName().displayValue,
            metaStorPool == null ? null : metaStorPool.getName().displayValue,
            allocatedSize,
            usableSize,
            diskState
        );
    }

    @Override
    public DeviceLayerKind getLayerKind()
    {
        return DeviceLayerKind.CACHE;
    }
}
