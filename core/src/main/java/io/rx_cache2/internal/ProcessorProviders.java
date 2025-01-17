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

package io.rx_cache2.internal;

import io.reactivex.rxjava3.core.Observable;
import io.rx_cache2.ConfigProvider;

/**
 * Entry point for RxCache
 */
public interface ProcessorProviders {

  /**
   * Provide the data from RxCache
   *
   * @param <T> the associated data
   * @return an observable based on the {@link ConfigProvider} specs.
   */
  <T> Observable<T> process(final ConfigProvider configProvider);

  /**
   * Destroy the entire cache
   */
  Observable<Void> evictAll();
}
