package com.linbit.linstor.dbdrivers;

import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.linstor.dbdrivers.interfaces.VolumeDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.updater.SingleColumnDatabaseDriver;
import com.linbit.linstor.dbdrivers.noop.NoOpFlagDriver;
import com.linbit.linstor.stateflags.StateFlagsPersistence;
import javax.inject.Inject;

public class SatelliteVolDfnDriver implements VolumeDefinitionDatabaseDriver
{
    private final StateFlagsPersistence<?> stateFlagsDriver = new NoOpFlagDriver();
    private final SingleColumnDatabaseDriver<?, ?> singleColDriver = new SatelliteSingleColDriver<>();

    @Inject
    public SatelliteVolDfnDriver()
    {
    }

    @SuppressWarnings("unchecked")
    @Override
    public StateFlagsPersistence<VolumeDefinition> getStateFlagsPersistence()
    {
        return (StateFlagsPersistence<VolumeDefinition>) stateFlagsDriver;
    }

    @SuppressWarnings("unchecked")
    @Override
    public SingleColumnDatabaseDriver<VolumeDefinition, Long> getVolumeSizeDriver()
    {
        return (SingleColumnDatabaseDriver<VolumeDefinition, Long>) singleColDriver;
    }

    @Override
    public void create(VolumeDefinition volDfnData)
    {
        // no-op
    }

    @Override
    public void delete(VolumeDefinition data)
    {
        // no-op
    }
}
