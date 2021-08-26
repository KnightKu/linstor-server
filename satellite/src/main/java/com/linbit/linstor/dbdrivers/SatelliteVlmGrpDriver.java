package com.linbit.linstor.dbdrivers;

import com.linbit.linstor.core.objects.VolumeGroup;
import com.linbit.linstor.dbdrivers.interfaces.VolumeGroupDatabaseDriver;
import com.linbit.linstor.dbdrivers.noop.NoOpFlagDriver;
import com.linbit.linstor.stateflags.StateFlagsPersistence;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SatelliteVlmGrpDriver implements VolumeGroupDatabaseDriver
{
    private final StateFlagsPersistence<?> stateFlagsDriver = new NoOpFlagDriver();

    @Inject
    public SatelliteVlmGrpDriver()
    {
    }

    @Override
    public void create(VolumeGroup vlmGrp) throws DatabaseException
    {
        // no-op
    }

    @Override
    public void delete(VolumeGroup vlmGrp) throws DatabaseException
    {
        // no-op
    }

    @Override
    public StateFlagsPersistence<VolumeGroup> getStateFlagsPersistence()
    {
        return (StateFlagsPersistence<VolumeGroup>) stateFlagsDriver;
    }
}
