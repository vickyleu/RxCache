/*
 * Copyright 2015 Victor Albertos
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

package io.rx_cache2.internal;

import javax.inject.Inject;

import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableSource;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.functions.Supplier;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.rx_cache2.ConfigProvider;
import io.rx_cache2.EvictDynamicKey;
import io.rx_cache2.EvictDynamicKeyGroup;
import io.rx_cache2.Reply;
import io.rx_cache2.RxCacheException;
import io.rx_cache2.Source;
import io.rx_cache2.internal.cache.GetDeepCopy;

public final class ProcessorProvidersBehaviour implements ProcessorProviders {
    private final io.rx_cache2.internal.cache.TwoLayersCache twoLayersCache;
    private final Boolean useExpiredDataIfLoaderNotAvailable;
    private final GetDeepCopy getDeepCopy;
    private final Observable<Integer> oProcesses;
    private volatile Boolean hasProcessesEnded;

    @Inject
    public ProcessorProvidersBehaviour(
            io.rx_cache2.internal.cache.TwoLayersCache twoLayersCache,
            Boolean useExpiredDataIfLoaderNotAvailable,
            io.rx_cache2.internal.cache.EvictExpiredRecordsPersistence evictExpiredRecordsPersistence,
            GetDeepCopy getDeepCopy, io.rx_cache2.internal.migration.DoMigrations doMigrations) {
        this.hasProcessesEnded = false;
        this.twoLayersCache = twoLayersCache;
        this.useExpiredDataIfLoaderNotAvailable = useExpiredDataIfLoaderNotAvailable;
        this.getDeepCopy = getDeepCopy;
        this.oProcesses = startProcesses(doMigrations, evictExpiredRecordsPersistence);
    }

    private Observable<Integer> startProcesses(
            io.rx_cache2.internal.migration.DoMigrations doMigrations,
            final io.rx_cache2.internal.cache.EvictExpiredRecordsPersistence evictExpiredRecordsPersistence) {
        Observable<Integer> oProcesses = doMigrations.react().flatMap((Function<Integer, ObservableSource<Integer>>) ignore ->
                evictExpiredRecordsPersistence.startEvictingExpiredRecords()).subscribeOn((Schedulers.io())).observeOn(Schedulers.io()).share();


        oProcesses.subscribe(ignore -> hasProcessesEnded = true);

        return oProcesses;
    }

    @Override
    public <T> Observable<T> process(final io.rx_cache2.ConfigProvider configProvider) {
        return Observable.defer(() -> {
            if (hasProcessesEnded) {
                return getData(configProvider);
            }
            return oProcesses.flatMap(ignore -> getData(configProvider));
        });
    }

    //VisibleForTesting
    <T> Observable<T> getData(final io.rx_cache2.ConfigProvider configProvider) {
        Record<Object> record = twoLayersCache.retrieve(configProvider.getProviderKey(), configProvider.getDynamicKey(),
                configProvider.getDynamicKeyGroup(), useExpiredDataIfLoaderNotAvailable,
                configProvider.getLifeTimeMillis(), configProvider.isEncrypted());

        Observable<Reply> replyObservable;
        if (record != null && !configProvider.evictProvider().evict()) {
            replyObservable = Observable.just(new Reply(record.getData(), record.getSource(), configProvider.isEncrypted()));
        } else {
            replyObservable = getDataFromLoader(configProvider, record);
        }
        return (Observable<T>) replyObservable.map(reply -> ProcessorProvidersBehaviour.this.getReturnType(configProvider, reply));
    }

    private @NonNull Observable getDataFromLoader(final ConfigProvider configProvider,
                                                  final Record record) {
        return configProvider.getLoaderObservable().map((Function<Object, Reply>) data -> {
            boolean useExpiredData = configProvider.useExpiredDataIfNotLoaderAvailable() != null ?
                    configProvider.useExpiredDataIfNotLoaderAvailable()
                    : useExpiredDataIfLoaderNotAvailable;

            if (data == null && useExpiredData && record != null) {
                return new Reply(record.getData(), record.getSource(), configProvider.isEncrypted());
            }

            clearKeyIfNeeded(configProvider);

            if (data == null) {
                throw new RxCacheException(Locale.NOT_DATA_RETURN_WHEN_CALLING_OBSERVABLE_LOADER
                        + " "
                        + configProvider.getProviderKey());
            }

            twoLayersCache.save(configProvider.getProviderKey(), configProvider.getDynamicKey(),
                    configProvider.getDynamicKeyGroup(), data, configProvider.getLifeTimeMillis(),
                    configProvider.isExpirable(), configProvider.isEncrypted());
            return new Reply(data, Source.CLOUD, configProvider.isEncrypted());
        }).onErrorReturn((Function<Object, Object>) o -> {
            clearKeyIfNeeded(configProvider);

            boolean useExpiredData = configProvider.useExpiredDataIfNotLoaderAvailable() != null ?
                    configProvider.useExpiredDataIfNotLoaderAvailable()
                    : useExpiredDataIfLoaderNotAvailable;

            if (useExpiredData && record != null) {
                return new Reply(record.getData(), record.getSource(), configProvider.isEncrypted());
            }

            throw new RxCacheException(Locale.NOT_DATA_RETURN_WHEN_CALLING_OBSERVABLE_LOADER
                    + " "
                    + configProvider.getProviderKey(), (Throwable) o);
        });
    }

    private void clearKeyIfNeeded(io.rx_cache2.ConfigProvider configProvider) {
        if (!configProvider.evictProvider().evict()) return;

        if (configProvider.evictProvider() instanceof EvictDynamicKeyGroup) {
            twoLayersCache.evictDynamicKeyGroup(configProvider.getProviderKey(),
                    configProvider.getDynamicKey(),
                    configProvider.getDynamicKeyGroup());
        } else if (configProvider.evictProvider() instanceof EvictDynamicKey) {
            twoLayersCache.evictDynamicKey(configProvider.getProviderKey(),
                    configProvider.getDynamicKey());
        } else {
            twoLayersCache.evictProviderKey(configProvider.getProviderKey());
        }
    }

    private Object getReturnType(io.rx_cache2.ConfigProvider configProvider, Reply reply) {
        Object data = getDeepCopy.deepCopy(reply.getData());

        if (configProvider.requiredDetailedResponse()) {
            return new Reply<>(data, reply.getSource(), configProvider.isEncrypted());
        } else {
            return data;
        }
    }

    @Override
    public Observable<Void> evictAll() {
        return Observable.defer((Supplier<ObservableSource<Void>>) () -> {
            ProcessorProvidersBehaviour.this.twoLayersCache.evictAll();
            return Completable.complete().toObservable();
        });
    }
}
