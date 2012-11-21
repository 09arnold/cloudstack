package org.apache.cloudstack.storage;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageProvider;

public interface StorageProviderManager {
    StorageProvider getProvider(String uuid);

    StorageProvider getProvider(long poolId);

    StorageProvider getBackupStorageProvider(long zoneId);

    DataStore getDataStore(long poolId);
}
