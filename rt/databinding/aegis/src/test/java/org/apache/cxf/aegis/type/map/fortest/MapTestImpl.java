/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.aegis.type.map.fortest;

import java.util.HashMap;
import java.util.Map;

public class MapTestImpl implements MapTest {

    public Map<String, Long> getMapStringToLong() {
        Map<String, Long> map = new HashMap<String, Long>();
        map.put("one", Long.valueOf(1));
        map.put("twenty-seven", Long.valueOf(27));
        return map;
    }

    public void takeMap(ObjectWithAMap map) {
    }

    public ObjectWithAMap returnObjectWithAMap() {
        return new ObjectWithAMap();
    }

    public Map<Long, String> getMapLongToString() {
        Map<Long, String> map = new HashMap<Long, String>();
        map.put(Long.valueOf(1), "one");
        map.put(Long.valueOf(27), "twenty-seven");
        return map;
    }

}
