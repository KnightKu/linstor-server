package com.linbit.linstor.core;

import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.utils.Pair;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Singleton
public class BackupInfoManager
{
    private Map<ResourceDefinition, String> restoreMap;
    private Map<String, Map<Pair<String, String>, List<AbortInfo>>> abortMap;
    private Map<Snapshot, LinkedList<Snapshot>> backupsToUpload;

    @Inject
    public BackupInfoManager(TransactionObjectFactory transObjFactoryRef)
    {
        restoreMap = transObjFactoryRef.createTransactionPrimitiveMap(new HashMap<>(), null);
        abortMap = new HashMap<>();
        backupsToUpload = new HashMap<>();
    }

    public boolean restoreAddEntry(ResourceDefinition rscDfn, String metaName)
    {
        synchronized (restoreMap)
        {
            if (!restoreMap.containsKey(rscDfn))
            {
                restoreMap.put(rscDfn, metaName);
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    public void restoreRemoveEntry(ResourceDefinition rscDfn)
    {
        synchronized (restoreMap)
        {
            restoreMap.remove(rscDfn);
        }
    }

    public boolean restoreContainsRscDfn(ResourceDefinition rscDfn)
    {
        synchronized (restoreMap)
        {
            return restoreMap.containsKey(rscDfn);
        }
    }

    public boolean restoreContainsMetaFile(String metaName)
    {
        synchronized (restoreMap)
        {
            return restoreMap.containsValue(metaName);
        }
    }

    public boolean restoreContainsMetaFile(String rscName, String snapName)
    {
        synchronized (restoreMap)
        {
            return restoreMap.containsValue(rscName + "_" + snapName + ".meta");
        }
    }

    public void abortAddEntry(
        String nodeName,
        String rscName,
        String snapName,
        String backupName,
        String uploadId,
        String remoteName
    )
    {
        Pair<String, String> pair = new Pair<>(rscName, snapName);

        synchronized (abortMap)
        {
            Map<Pair<String, String>, List<AbortInfo>> map = abortMap.get(nodeName);
            if (map != null)
            {
                if (map.containsKey(pair))
                {
                    map.get(pair).add(new AbortInfo(backupName, uploadId, remoteName));
                }
                else
                {
                    map.put(pair, new ArrayList<>());
                    map.get(pair).add(new AbortInfo(backupName, uploadId, remoteName));
                }
            }
            else
            {
                abortMap.put(nodeName, new HashMap<>());
                map = abortMap.get(nodeName);
                map.put(pair, new ArrayList<>());
                map.get(pair).add(new AbortInfo(backupName, uploadId, remoteName));
            }
        }
    }

    public void abortDeleteEntries(String nodeName, String rscName, String snapName)
    {
        Pair<String, String> pair = new Pair<>(rscName, snapName);

        synchronized (abortMap)
        {
            Map<Pair<String, String>, List<AbortInfo>> map = abortMap.get(nodeName);
            if (map != null)
            {
                map.remove(pair);
            }
        }
    }

    public Map<Pair<String, String>, List<AbortInfo>> abortGetEntries(String nodeName)
    {
        synchronized (abortMap)
        {
            return abortMap.get(nodeName);
        }
    }

    public boolean backupsToUploadAddEntry(Snapshot snap, LinkedList<Snapshot> backups)
    {
        synchronized (backupsToUpload)
        {
            if (backupsToUpload.containsKey(snap))
            {
                return false;
            }
            backupsToUpload.put(snap, backups);
            return true;
        }
    }

    public Snapshot getNextBackupToUpload(Snapshot snap)
    {
        synchronized (backupsToUpload)
        {
            return backupsToUpload.get(snap).pollFirst();
        }
    }

    public void backupsToUploadRemoveEntry(Snapshot snap)
    {
        synchronized (backupsToUpload)
        {
            backupsToUpload.remove(snap);
        }
    }

    public class AbortInfo
    {
        public final String backupName;
        public final String uploadId;
        public final String remoteName;

        AbortInfo(String backupNameRef, String uploadIdRef, String remoteNameRef)
        {
            backupName = backupNameRef;
            uploadId = uploadIdRef;
            remoteName = remoteNameRef;
        }
    }

}
