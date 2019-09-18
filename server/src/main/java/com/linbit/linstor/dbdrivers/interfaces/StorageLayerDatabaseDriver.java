package com.linbit.linstor.dbdrivers.interfaces;

import com.linbit.SingleColumnDatabaseDriver;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.StorPoolName;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.data.provider.StorageRscData;
import com.linbit.linstor.storage.interfaces.categories.resource.RscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.utils.Pair;

import java.util.Map;
import java.util.Set;

public interface StorageLayerDatabaseDriver
{
    ResourceLayerIdDatabaseDriver getIdDriver();

    void persist(StorageRscData storageRscDataRef) throws DatabaseException;
    void delete(StorageRscData storgeRscDataRef) throws DatabaseException;

    void persist(VlmProviderObject vlmDataRef) throws DatabaseException;
    void delete(VlmProviderObject vlmDataRef) throws DatabaseException;

    SingleColumnDatabaseDriver<VlmProviderObject, StorPool> getStorPoolDriver();

    // methods only used for loading
    void fetchForLoadAll(Map<Pair<NodeName, StorPoolName>, Pair<StorPool, StorPool.InitMaps>> tmpStorPoolMapRef)
        throws DatabaseException;
    void loadLayerData(Map<ResourceName, ResourceDefinition> tmpRscDfnMapRef) throws DatabaseException;
    void clearLoadAllCache();
    Pair<? extends RscLayerObject, Set<RscLayerObject>> load(
            Resource rscRef,
            int idRef,
            String rscSuffixRef,
            RscLayerObject parentRef
    )
        throws AccessDeniedException, DatabaseException;
}
