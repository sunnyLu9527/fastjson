/*
 * Copyright 1999-2017 Alibaba Group.
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
package com.alibaba.fastjson.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER })
public @interface JSONField {

    //https://github.com/alibaba/fastjson/wiki/JSONField
    /**
     * config encode/decode ordinal  配置序列化和反序列化的顺序
     * @since 1.1.42  1.1.42版本之后才支持
     * @return
     */
    int ordinal() default 0;

    //指定字段的名称
    String name() default "";

    /**
     * 指定字段的格式，对日期格式有用
     * 亦可支持较大的byte[]输出 具体见 https://github.com/alibaba/fastjson/wiki/JSONField_format_gzip
     * @return
     */
    String format() default "";

    //是否序列化
    boolean serialize() default true;

    //是否反序列化
    boolean deserialize() default true;

    SerializerFeature[] serialzeFeatures() default {};

    Feature[] parseFeatures() default {};
    
    String label() default "";
    
    /**
     * 具体见 https://github.com/alibaba/fastjson/wiki/JSONField_jsonDirect_cn
     * @since 1.2.12
     */
    boolean jsonDirect() default false;
    
    /**
     * Serializer class to use for serializing associated value.
     * 
     * @since 1.2.16
     */
    Class<?> serializeUsing() default Void.class;
    
    /**
     * Deserializer class to use for deserializing associated value. 
     * 
     * @since 1.2.16 
     */
    Class<?> deserializeUsing() default Void.class;

    /**
     * @since 1.2.21
     * @return the alternative names of the field when it is deserialized
     * 反序列化支持多个不同字段名称
     * https://github.com/alibaba/fastjson/wiki/JSONField_alternateNames_cn
     */
    String[] alternateNames() default {};

    /**
     * 具体见 https://github.com/alibaba/fastjson/wiki/JSONField_unwrapped_cn
     * @since 1.2.31
     */
    boolean unwrapped() default false;
}
