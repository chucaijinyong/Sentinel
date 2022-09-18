/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.property;

import com.alibaba.csp.sentinel.log.RecordLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DynamicSentinelProperty<T> implements SentinelProperty<T> {

    /**
     * 属性监听器集合
     */
    protected Set<PropertyListener<T>> listeners = Collections.synchronizedSet(new HashSet<PropertyListener<T>>());
    /**
     * 规则
     */
    private T value = null;

    public DynamicSentinelProperty() {
    }

    public DynamicSentinelProperty(T value) {
        super();
        this.value = value;
    }

    /**
     * 增加监听器并将规则刷新本地内存
     */
    @Override
    public void addListener(PropertyListener<T> listener) {
        listeners.add(listener);
        // 将规则刷新本地内存
        listener.configLoad(value);
    }

    @Override
    public void removeListener(PropertyListener<T> listener) {
        listeners.remove(listener);
    }

    /**
    * 通过sentinel的监听器来实现的规则更新
    */
    @Override
    public boolean updateValue(T newValue) {
        if (isEqual(value, newValue)) {
            return false;
        }
        RecordLog.info("[DynamicSentinelProperty] Config will be updated to: {}", newValue);

        value = newValue;
        // 监听器，触发规则更新
        for (PropertyListener<T> listener : listeners) {
            listener.configUpdate(newValue);
        }
        return true;
    }

    private boolean isEqual(T oldValue, T newValue) {
        if (oldValue == null && newValue == null) {
            return true;
        }

        if (oldValue == null) {
            return false;
        }

        return oldValue.equals(newValue);
    }

    public void close() {
        listeners.clear();
    }
}
