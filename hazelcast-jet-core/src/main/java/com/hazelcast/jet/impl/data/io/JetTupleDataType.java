/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl.data.io;

import com.hazelcast.jet.data.tuple.JetTuple;
import com.hazelcast.jet.data.tuple.JetTuple2;
import com.hazelcast.jet.io.DataType;
import com.hazelcast.jet.io.IOContext;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;

public final class JetTupleDataType implements DataType {
    public static final byte TYPE_ID = -4;

    public static final DataType INSTANCE = new JetTupleDataType();

    private JetTupleDataType() {
    }

    @Override
    public Class getClazz() {
        return JetTuple.class;
    }

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public void write(Object o, ObjectDataOutput objectDataOutput, IOContext ioContext) throws IOException {
        objectDataOutput.writeByte(TYPE_ID);
        for (int i = 0; i < 2; i++) {
            final Object component = ((JetTuple) o).get(i);
            ioContext.resolveDataType(component).write(component, objectDataOutput, ioContext);
        }
    }

    @Override
    public Object read(ObjectDataInput objectDataInput, IOContext ioContext) throws IOException {
        return new JetTuple2<>(readComponent(objectDataInput, ioContext), readComponent(objectDataInput, ioContext));
    }

    private static Object readComponent(ObjectDataInput objectDataInput, IOContext ioContext) throws IOException {
        final byte typeID = objectDataInput.readByte();
        return ioContext.lookupDataType(typeID).read(objectDataInput, ioContext);
    }
}