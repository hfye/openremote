/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.openremote.model.value;

/**
 * Represents the type of the underlying value, the same as in JSON.
 */
public enum ValueType {

    OBJECT(ObjectValue.class),
    ARRAY(ArrayValue.class),
    STRING(StringValue.class),
    NUMBER(NumberValue.class),
    BOOLEAN(BooleanValue.class);

    protected final Class<? extends Value> modelType;

    ValueType(Class<? extends Value> type) {
        this.modelType = type;
    }

    public Class getModelType() {
        return modelType;
    }
}
