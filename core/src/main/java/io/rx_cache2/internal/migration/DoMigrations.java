/*
 * Copyright 2016 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rx_cache2.internal.migration;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableSource;
import io.reactivex.rxjava3.functions.Function;
import io.rx_cache2.MigrationCache;
import io.rx_cache2.internal.Persistence;

public final class DoMigrations {
    private final io.rx_cache2.internal.migration.GetCacheVersion getCacheVersion;
    private final io.rx_cache2.internal.migration.GetPendingMigrations getPendingMigrations;
    private final io.rx_cache2.internal.migration.DeleteRecordMatchingClassName
            deleteRecordMatchingClassName;
    private final io.rx_cache2.internal.migration.UpgradeCacheVersion upgradeCacheVersion;
    private final GetClassesToEvictFromMigrations getClassesToEvictFromMigrations;
    private final List<MigrationCache> migrations;

    @Inject
    public DoMigrations(Persistence persistence, List<MigrationCache> migrations,
                        String encryptKey) {
        this.getClassesToEvictFromMigrations = new GetClassesToEvictFromMigrations();
        this.getCacheVersion = new io.rx_cache2.internal.migration.GetCacheVersion(persistence);
        this.getPendingMigrations = new io.rx_cache2.internal.migration.GetPendingMigrations();
        this.migrations = migrations;
        this.upgradeCacheVersion = new io.rx_cache2.internal.migration.UpgradeCacheVersion(persistence);
        this.deleteRecordMatchingClassName = new io.rx_cache2.internal.migration.DeleteRecordMatchingClassName(persistence, encryptKey);
    }

    public Observable<Integer> react() {
        return getCacheVersion.react()
                .flatMap((Function<Integer, ObservableSource<List<MigrationCache>>>) currentCacheVersion ->
                        getPendingMigrations.with(currentCacheVersion, migrations).react())
                .flatMap((Function<List<MigrationCache>, ObservableSource<List<Class>>>) migrationCaches ->
                        getClassesToEvictFromMigrations.with(migrationCaches).react())
                .flatMap((Function<List<Class>, ObservableSource<Integer>>) classes ->
                        deleteRecordMatchingClassName.with(classes).react())
                .flatMap((Function<Integer, ObservableSource<Integer>>) ignore -> upgradeCacheVersion.with(migrations).react());
    }
}
