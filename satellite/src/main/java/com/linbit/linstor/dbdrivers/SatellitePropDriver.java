package com.linbit.linstor.dbdrivers;

import com.linbit.linstor.dbdrivers.interfaces.PropsConDatabaseDriver;
import javax.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class SatellitePropDriver implements PropsConDatabaseDriver
{
    @Inject
    public SatellitePropDriver()
    {
    }

    @Override
    public Map<String, String> loadAll(String instanceName)
    {
        return Collections.emptyMap();
    }

    @Override
    public void persist(String instanceName, String key, String value, boolean isNew)
    {
        // no-op
    }

    @Override
    public void remove(String instanceName, String key)
    {
        // no-op
    }

    @Override
    public void remove(String instanceName, Set<String> keys)
    {
        // no-op
    }

    @Override
    public void removeAll(String instanceName)
    {
        // no-op
    }
}
