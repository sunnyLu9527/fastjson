/*
 * Copyright 1999-2018 Alibaba Group.
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
package com.alibaba.fastjson.serializer;

public interface PropertyPreFilter extends SerializeFilter {

    /***
    * @Param [serializer, object--代表当前正在做序列化的对象, name--代表当前在序列化的属性的名字]
    * @description
    * @author luming
    * @date 2020/6/12 10:37
    * @return boolean true代表这个属性会被序列化，false代表这个属性不会被序列化
    * @throws
    */
    boolean apply(JSONSerializer serializer, Object object, String name);
}
