/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.datamodel;

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nonnull;

import static com.hazelcast.jet.impl.util.Util.toLocalTime;

/**
 * Represents a data item with the event timestamp added to it. Used in the
 * Pipeline API as the default data type to emit from global windowed
 * aggregation stages (those that aggregate all the data in a window, without
 * grouping).
 *
 * @param <T> type of the item
 */
public final class TimestampedItem<T> implements Serializable {
    private final long timestamp;
    private final T item;

    /**
     * Constructs a timestamped item with the supplied field values.
     */
    public TimestampedItem(long timestamp, @Nonnull T item) {
        this.timestamp = timestamp;
        this.item = item;
    }

    /**
     * This constructor exists in order to match the shape of the functional
     * interface {@link com.hazelcast.jet.function.WindowResultFunction
     * WindowResultFunction}.
     * <p>
     * Constructs a timestamped item with the supplied field values. Ignores
     * the first argument.
     */
    public TimestampedItem(long ignored, long timestamp, @Nonnull T item) {
        this(timestamp, item);
    }

    /**
     * Returns the timestamp associated with the item.
     */
    public long timestamp() {
        return timestamp;
    }

    /**
     * Returns the item.
     */
    public T item() {
        return item;
    }

    @Override
    public int hashCode() {
        int hc = 17;
        hc = 73 * hc + Long.hashCode(timestamp);
        hc = 73 * hc + Objects.hashCode(item);
        return hc;
    }

    @Override
    public boolean equals(Object obj) {
        final TimestampedItem that;
        return this == obj
                || obj instanceof TimestampedItem
                && this.timestamp == (that = (TimestampedItem) obj).timestamp
                && Objects.equals(this.item, that.item);
    }

    @Override
    public String toString() {
        return "TimestampedItem{ts=" + toLocalTime(timestamp)
                + ", value='" + item + "'}";
    }
}
