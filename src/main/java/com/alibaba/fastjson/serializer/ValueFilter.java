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

/***
* @Param
* @description 改变序列化之后的属性值
* @author luming
* @date 2020/6/12 11:20
* @return
* @throws
*/
public interface ValueFilter extends SerializeFilter {

    /**
     * 官方注释很明显了吧，不须要再解释了
     * @param object the owner of the property
     * @param name the name of the property
     * @param value the value of the property
     * @return the final value
     */
    Object process(Object object, String name, Object value);
}
