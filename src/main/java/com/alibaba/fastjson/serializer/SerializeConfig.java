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

import com.alibaba.fastjson.*;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.parser.deserializer.Jdk8DateCodec;
import com.alibaba.fastjson.parser.deserializer.OptionalCodec;
import com.alibaba.fastjson.support.springfox.SwaggerJsonSerializer;
import com.alibaba.fastjson.util.*;
import com.alibaba.fastjson.util.IdentityHashMap;
import com.alibaba.fastjson.util.ServiceLoader;

import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

/**
 * circular references detect
 * 
 * @author wenshao[szujobs@hotmail.com]
 */
public class SerializeConfig {

    public final static SerializeConfig                   globalInstance  = new SerializeConfig();

    private static boolean                                awtError        = false;
    private static boolean                                jdk8Error       = false;
    private static boolean                                oracleJdbcError = false;
    private static boolean                                springfoxError  = false;
    private static boolean                                guavaError      = false;
    private static boolean                                jsonnullError   = false;

    private static boolean                                jodaError       = false;

    private boolean                                       asm             = !ASMUtils.IS_ANDROID;
    private ASMSerializerFactory                          asmFactory;
    protected String                                      typeKey         = JSON.DEFAULT_TYPE_KEY;
    public PropertyNamingStrategy                         propertyNamingStrategy;

    private final IdentityHashMap<Type, ObjectSerializer> serializers;

    private final boolean                                 fieldBased;

    private long[]                                        denyClasses =
            {
                    4165360493669296979L,
                    4446674157046724083L
            };
    
	public String getTypeKey() {
		return typeKey;
	}

	public void setTypeKey(String typeKey) {
		this.typeKey = typeKey;
	}
	
    private final JavaBeanSerializer createASMSerializer(SerializeBeanInfo beanInfo) throws Exception {
        JavaBeanSerializer serializer = asmFactory.createJavaBeanSerializer(beanInfo);
        
        for (int i = 0; i < serializer.sortedGetters.length; ++i) {
            FieldSerializer fieldDeser = serializer.sortedGetters[i];
            Class<?> fieldClass = fieldDeser.fieldInfo.fieldClass;
            if (fieldClass.isEnum()) {
                ObjectSerializer fieldSer = this.getObjectWriter(fieldClass);
                if (!(fieldSer instanceof EnumSerializer)) {
                    serializer.writeDirect = false;
                }
            }
        }
     
        return serializer;
    }

    public final ObjectSerializer createJavaBeanSerializer(Class<?> clazz) {
        String className = clazz.getName();
        long hashCode64 = TypeUtils.fnv1a_64(className);
	    if (Arrays.binarySearch(denyClasses, hashCode64) >= 0) {
	        throw new JSONException("not support class : " + className);
        }
        /** 封装序列化clazz Bean，包含字段类型等等 */
	    SerializeBeanInfo beanInfo = TypeUtils.buildBeanInfo(clazz, null, propertyNamingStrategy, fieldBased);
	    if (beanInfo.fields.length == 0 && Iterable.class.isAssignableFrom(clazz)) {
            /** 如果clazz是迭代器类型，使用MiscCodec序列化，会被序列化成数组 [,,,] */
	        return MiscCodec.instance;
	    }

	    return createJavaBeanSerializer(beanInfo);
	}
	
	public ObjectSerializer createJavaBeanSerializer(SerializeBeanInfo beanInfo) {
        JSONType jsonType = beanInfo.jsonType;

        boolean asm = this.asm && !fieldBased;

        if (jsonType != null) {
            Class<?> serializerClass = jsonType.serializer();
            if (serializerClass != Void.class) {
                try {
                    /** 实例化注解指定的类型 */
                    Object seralizer = serializerClass.newInstance();
                    if (seralizer instanceof ObjectSerializer) {
                        return (ObjectSerializer) seralizer;
                    }
                } catch (Throwable e) {
                    // skip
                }
            }
            if (jsonType.asm() == false) {
                asm = false;
            }
	        if (asm) {
                /** 注解显示开启WriteNonStringValueAsString、WriteEnumUsingToString
                 * 和NotWriteDefaultValue不使用asm */
                for (SerializerFeature feature : jsonType.serialzeFeatures()) {
                    if (SerializerFeature.WriteNonStringValueAsString == feature //
                            || SerializerFeature.WriteEnumUsingToString == feature //
                            || SerializerFeature.NotWriteDefaultValue == feature
                            || SerializerFeature.BrowserCompatible == feature) {
                        asm = false;
                        break;
                    }
                }
            }

            if (asm) {
                final Class<? extends SerializeFilter>[] filterClasses = jsonType.serialzeFilters();
                if (filterClasses.length != 0) {
                    asm = false;
                }
            }
        }

        Class<?> clazz = beanInfo.beanType;
        /** 非public类型，直接使用JavaBeanSerializer序列化 */
        if (!Modifier.isPublic(beanInfo.beanType.getModifiers())) {
            return new JavaBeanSerializer(beanInfo);
        }

        if (asm && asmFactory.classLoader.isExternalClass(clazz)
                || clazz == Serializable.class || clazz == Object.class) {
            asm = false;
        }

        if (asm && !ASMUtils.checkName(clazz.getSimpleName())) {
            asm = false;
        }

        if (asm && beanInfo.beanType.isInterface()) {
            asm = false;
        }

        if (asm) {
            for(FieldInfo fieldInfo : beanInfo.fields){
                Field field = fieldInfo.field;
                if (field != null && !field.getType().equals(fieldInfo.fieldClass)) {
                    asm = false;
                    break;
                }

                Method method = fieldInfo.method;
                if (method != null && !method.getReturnType().equals(fieldInfo.fieldClass)) {
                    asm = false;
                    break;
                }

                JSONField annotation = fieldInfo.getAnnotation();

                if (annotation == null) {
                    continue;
                }

                String format = annotation.format();
                if (format.length() != 0) {
                    if (fieldInfo.fieldClass == String.class && "trim".equals(format)) {

                    } else {
                        asm = false;
                        break;
                    }
                }

                if ((!ASMUtils.checkName(annotation.name())) //
                        || annotation.jsonDirect()
                        || annotation.serializeUsing() != Void.class
                        || annotation.unwrapped()
                        ) {
                    asm = false;
                    break;
                }

                for (SerializerFeature feature : annotation.serialzeFeatures()) {
                    if (SerializerFeature.WriteNonStringValueAsString == feature //
                            || SerializerFeature.WriteEnumUsingToString == feature //
                            || SerializerFeature.NotWriteDefaultValue == feature
                            || SerializerFeature.BrowserCompatible == feature
                            || SerializerFeature.WriteClassName == feature) {
                        asm = false;
                        break;
                    }
                }

                if (TypeUtils.isAnnotationPresentOneToMany(method) || TypeUtils.isAnnotationPresentManyToMany(method)) {
                    asm = false;
                    break;
                }
            }
        }

        if (asm) {
            try {
                ObjectSerializer asmSerializer = createASMSerializer(beanInfo);
                if (asmSerializer != null) {
                    return asmSerializer;
                }
            } catch (ClassNotFoundException ex) {
                // skip
            } catch (ClassFormatError e) {
                // skip
            } catch (ClassCastException e) {
                // skip
            } catch (OutOfMemoryError e) {
                if (e.getMessage().indexOf("Metaspace") != -1) {
                    throw e;
                }
                // skip
            } catch (Throwable e) {
                throw new JSONException("create asm serializer error, verson " + JSON.VERSION + ", class " + clazz, e);
            }
        }

        /** 默认使用JavaBeanSerializer 序列化类 */
        return new JavaBeanSerializer(beanInfo);
	}

	public boolean isAsmEnable() {
		return asm;
	}

	public void setAsmEnable(boolean asmEnable) {
	    if (ASMUtils.IS_ANDROID) {
	        return;
	    }
		this.asm = asmEnable;
	}

	public static SerializeConfig getGlobalInstance() {
		return globalInstance;
	}

	public SerializeConfig() {
		this(IdentityHashMap.DEFAULT_SIZE);
	}

    public SerializeConfig(boolean fieldBase) {
	    this(IdentityHashMap.DEFAULT_SIZE, fieldBase);
    }

    public SerializeConfig(int tableSize) {
        this(tableSize, false);
    }

	public SerializeConfig(int tableSize, boolean fieldBase) {
	    this.fieldBased = fieldBase;
	    serializers = new IdentityHashMap<Type, ObjectSerializer>(tableSize);
		
		try {
		    if (asm) {
		        asmFactory = new ASMSerializerFactory();
		    }
		} catch (Throwable eror) {
		    asm = false;
		}

        initSerializers();
	}

    private void initSerializers() {
        put(Boolean.class, BooleanCodec.instance);
        put(Character.class, CharacterCodec.instance);
        put(Byte.class, IntegerCodec.instance);
        put(Short.class, IntegerCodec.instance);
        put(Integer.class, IntegerCodec.instance);
        put(Long.class, LongCodec.instance);
        put(Float.class, FloatCodec.instance);
        put(Double.class, DoubleSerializer.instance);
        put(BigDecimal.class, BigDecimalCodec.instance);
        put(BigInteger.class, BigIntegerCodec.instance);
        put(String.class, StringCodec.instance);
        put(byte[].class, PrimitiveArraySerializer.instance);
        put(short[].class, PrimitiveArraySerializer.instance);
        put(int[].class, PrimitiveArraySerializer.instance);
        put(long[].class, PrimitiveArraySerializer.instance);
        put(float[].class, PrimitiveArraySerializer.instance);
        put(double[].class, PrimitiveArraySerializer.instance);
        put(boolean[].class, PrimitiveArraySerializer.instance);
        put(char[].class, PrimitiveArraySerializer.instance);
        put(Object[].class, ObjectArrayCodec.instance);
        put(Class.class, MiscCodec.instance);

        put(SimpleDateFormat.class, MiscCodec.instance);
        put(Currency.class, new MiscCodec());
        put(TimeZone.class, MiscCodec.instance);
        put(InetAddress.class, MiscCodec.instance);
        put(Inet4Address.class, MiscCodec.instance);
        put(Inet6Address.class, MiscCodec.instance);
        put(InetSocketAddress.class, MiscCodec.instance);
        put(File.class, MiscCodec.instance);
        put(Appendable.class, AppendableSerializer.instance);
        put(StringBuffer.class, AppendableSerializer.instance);
        put(StringBuilder.class, AppendableSerializer.instance);
        put(Charset.class, ToStringSerializer.instance);
        put(Pattern.class, ToStringSerializer.instance);
        put(Locale.class, ToStringSerializer.instance);
        put(URI.class, ToStringSerializer.instance);
        put(URL.class, ToStringSerializer.instance);
        put(UUID.class, ToStringSerializer.instance);

        // atomic
        put(AtomicBoolean.class, AtomicCodec.instance);
        put(AtomicInteger.class, AtomicCodec.instance);
        put(AtomicLong.class, AtomicCodec.instance);
        put(AtomicReference.class, ReferenceCodec.instance);
        put(AtomicIntegerArray.class, AtomicCodec.instance);
        put(AtomicLongArray.class, AtomicCodec.instance);

        put(WeakReference.class, ReferenceCodec.instance);
        put(SoftReference.class, ReferenceCodec.instance);

        put(LinkedList.class, CollectionCodec.instance);
    }

    /**
	 * add class level serialize filter
	 * @since 1.2.10
	 */
	public void addFilter(Class<?> clazz, SerializeFilter filter) {
	    ObjectSerializer serializer = getObjectWriter(clazz);
	    
	    if (serializer instanceof SerializeFilterable) {
	        SerializeFilterable filterable = (SerializeFilterable) serializer;
	        
	        if (this != SerializeConfig.globalInstance) {
	            if (filterable == MapSerializer.instance) {
	                MapSerializer newMapSer = new MapSerializer();
	                this.put(clazz, newMapSer);
	                newMapSer.addFilter(filter);
	                return;
	            }
	        }
	        
	        filterable.addFilter(filter);
	    }
	}
	
    /** class level serializer feature config
     * @since 1.2.12
     */
    public void config(Class<?> clazz, SerializerFeature feature, boolean value) {
        ObjectSerializer serializer = getObjectWriter(clazz, false);
        
        if (serializer == null) {
            SerializeBeanInfo beanInfo = TypeUtils.buildBeanInfo(clazz, null, propertyNamingStrategy);
            
            if (value) {
                beanInfo.features |= feature.mask;
            } else {
                beanInfo.features &= ~feature.mask;
            }
            
            serializer = this.createJavaBeanSerializer(beanInfo);
            
            put(clazz, serializer);
            return;
        }

        if (serializer instanceof JavaBeanSerializer) {
            JavaBeanSerializer javaBeanSerializer = (JavaBeanSerializer) serializer;
            SerializeBeanInfo beanInfo = javaBeanSerializer.beanInfo;
            
            int originalFeaturs = beanInfo.features;
            if (value) {
                beanInfo.features |= feature.mask;
            } else {
                beanInfo.features &= ~feature.mask;
            }
            
            if (originalFeaturs == beanInfo.features) {
                return;
            }
            
            Class<?> serializerClass = serializer.getClass();
            if (serializerClass != JavaBeanSerializer.class) {
                ObjectSerializer newSerializer = this.createJavaBeanSerializer(beanInfo);
                this.put(clazz, newSerializer);
            }
        }
    }
    
    public ObjectSerializer getObjectWriter(Class<?> clazz) {
        return getObjectWriter(clazz, true);
    }
	
	private ObjectSerializer getObjectWriter(Class<?> clazz, boolean create) {
        /** 首先从内部已经注册查找特定class的序列化实例 */
        ObjectSerializer writer = serializers.get(clazz);

        if (writer == null) {
            try {
                final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                /** 使用当前线程类加载器 查找 META-INF/services/AutowiredObjectSerializer.class实现类 */
                for (Object o : ServiceLoader.load(AutowiredObjectSerializer.class, classLoader)) {
                    if (!(o instanceof AutowiredObjectSerializer)) {
                        continue;
                    }

                    AutowiredObjectSerializer autowired = (AutowiredObjectSerializer) o;
                    for (Type forType : autowired.getAutowiredFor()) {
                        /** 如果存在，注册到内部serializers缓存中 */
                        put(forType, autowired);
                    }
                }
            } catch (ClassCastException ex) {
                // skip
            }

            /** 尝试在已注册缓存找到特定class的序列化实例 */
            writer = serializers.get(clazz);
        }

        if (writer == null) {
            /** 使用加载JSON类的加载器 查找 META-INF/services/AutowiredObjectSerializer.class实现类 */
            final ClassLoader classLoader = JSON.class.getClassLoader();
            if (classLoader != Thread.currentThread().getContextClassLoader()) {
                try {
                    for (Object o : ServiceLoader.load(AutowiredObjectSerializer.class, classLoader)) {

                        if (!(o instanceof AutowiredObjectSerializer)) {
                            continue;
                        }

                        AutowiredObjectSerializer autowired = (AutowiredObjectSerializer) o;
                        for (Type forType : autowired.getAutowiredFor()) {
                            /** 如果存在，注册到内部serializers缓存中 */
                            put(forType, autowired);
                        }
                    }
                } catch (ClassCastException ex) {
                    // skip
                }

                /** 尝试在已注册缓存找到特定class的序列化实例 */
                writer = serializers.get(clazz);
            }
        }

        if (writer == null) {
            String className = clazz.getName();
            Class<?> superClass;

            if (Map.class.isAssignableFrom(clazz)) {
                /** 如果class实现类Map接口，使用MapSerializer序列化 */
                put(clazz, writer = MapSerializer.instance);
            } else if (List.class.isAssignableFrom(clazz)) {
                /** 如果class实现类List接口，使用ListSerializer序列化 */
                put(clazz, writer = ListSerializer.instance);
            } else if (Collection.class.isAssignableFrom(clazz)) {
                /** 如果class实现类Collection接口，使用CollectionCodec序列化 */
                put(clazz, writer = CollectionCodec.instance);
            } else if (Date.class.isAssignableFrom(clazz)) {
                /** 如果class继承Date，使用DateCodec序列化 */
                put(clazz, writer = DateCodec.instance);
            } else if (JSONAware.class.isAssignableFrom(clazz)) {
                /** 如果class实现类JSONAware接口，使用JSONAwareSerializer序列化 */
                put(clazz, writer = JSONAwareSerializer.instance);
            } else if (JSONSerializable.class.isAssignableFrom(clazz)) {
                /** 如果class实现类JSONSerializable接口，使用JSONSerializableSerializer序列化 */
                put(clazz, writer = JSONSerializableSerializer.instance);
            } else if (JSONStreamAware.class.isAssignableFrom(clazz)) {
                /** 如果class实现类JSONStreamAware接口，使用MiscCodecr序列化 */
                put(clazz, writer = MiscCodec.instance);
            } else if (clazz.isEnum()) {
                JSONType jsonType = TypeUtils.getAnnotation(clazz, JSONType.class);
                if (jsonType != null && jsonType.serializeEnumAsJavaBean()) {
                    /** 如果是枚举类型，并且启用特性 serializeEnumAsJavaBean
                     *  使用JavaBeanSerializer序列化(假设没有启用asm)
                     */
                    put(clazz, writer = createJavaBeanSerializer(clazz));
                } else {
                    /** 如果是枚举类型，没有启用特性 serializeEnumAsJavaBean
                     *  使用EnumSerializer序列化
                     */
                    put(clazz, writer = EnumSerializer.instance);
                }
            } else if ((superClass = clazz.getSuperclass()) != null && superClass.isEnum()) {
                JSONType jsonType = TypeUtils.getAnnotation(superClass, JSONType.class);
                if (jsonType != null && jsonType.serializeEnumAsJavaBean()) {
                    /** 如果父类是枚举类型，并且启用特性 serializeEnumAsJavaBean
                     *  使用JavaBeanSerializer序列化(假设没有启用asm)
                     */
                    put(clazz, writer = createJavaBeanSerializer(clazz));
                } else {
                    /** 如果父类是枚举类型，没有启用特性 serializeEnumAsJavaBean
                     *  使用EnumSerializer序列化
                     */
                    put(clazz, writer = EnumSerializer.instance);
                }
            } else if (clazz.isArray()) {
                Class<?> componentType = clazz.getComponentType();
                /** 如果是数组类型，根据数组实际类型查找序列化实例 */
                ObjectSerializer compObjectSerializer = getObjectWriter(componentType);
                put(clazz, writer = new ArraySerializer(componentType, compObjectSerializer));
            } else if (Throwable.class.isAssignableFrom(clazz)) {
                /** 注册通用JavaBeanSerializer序列化处理 Throwable */
                SerializeBeanInfo beanInfo = TypeUtils.buildBeanInfo(clazz, null, propertyNamingStrategy);
                beanInfo.features |= SerializerFeature.WriteClassName.mask;
                put(clazz, writer = new JavaBeanSerializer(beanInfo));
            } else if (TimeZone.class.isAssignableFrom(clazz) || Map.Entry.class.isAssignableFrom(clazz)) {
                /** 如果class实现Map.Entry接口或者继承类TimeZone，使用MiscCodecr序列化 */
                put(clazz, writer = MiscCodec.instance);
            } else if (Appendable.class.isAssignableFrom(clazz)) {
                /** 如果class实现Appendable接口，使用AppendableSerializer序列化 */
                put(clazz, writer = AppendableSerializer.instance);
            } else if (Charset.class.isAssignableFrom(clazz)) {
                /** 如果class继承Charset抽象类，使用ToStringSerializer序列化 */
                put(clazz, writer = ToStringSerializer.instance);
            } else if (Enumeration.class.isAssignableFrom(clazz)) {
                /** 如果class实现Enumeration接口，使用EnumerationSerializer序列化 */
                put(clazz, writer = EnumerationSerializer.instance);
            } else if (Calendar.class.isAssignableFrom(clazz)
                    || XMLGregorianCalendar.class.isAssignableFrom(clazz)) {
                /** 如果class继承类Calendar或者XMLGregorianCalendar，使用CalendarCodec序列化 */
                put(clazz, writer = CalendarCodec.instance);
            } else if (TypeUtils.isClob(clazz)) {
                /** 如果class实现Clob接口，使用ClobSeriliazer序列化 */
                put(clazz, writer = ClobSeriliazer.instance);
            } else if (TypeUtils.isPath(clazz)) {
                /** 如果class实现java.nio.file.Path接口，使用ToStringSerializer序列化 */
                put(clazz, writer = ToStringSerializer.instance);
            } else if (Iterator.class.isAssignableFrom(clazz)) {
                /** 如果class实现Iterator接口，使用MiscCodec序列化 */
                put(clazz, writer = MiscCodec.instance);
            } else if (org.w3c.dom.Node.class.isAssignableFrom(clazz)) {
                put(clazz, writer = MiscCodec.instance);
            } else {
                /**
                 *  如果class的name是"java.awt."开头 并且
                 *  继承 Point、Rectangle、Font或者Color 其中之一
                 */
                if (className.startsWith("java.awt.")
                        && AwtCodec.support(clazz)
                        ) {
                    // awt
                    if (!awtError) {
                        try {
                            String[] names = new String[]{
                                    "java.awt.Color",
                                    "java.awt.Font",
                                    "java.awt.Point",
                                    "java.awt.Rectangle"
                            };
                            for (String name : names) {
                                if (name.equals(className)) {
                                    /** 如果系统支持4中类型， 使用AwtCodec 序列化 */
                                    put(Class.forName(name), writer = AwtCodec.instance);
                                    return writer;
                                }
                            }
                        } catch (Throwable e) {
                            awtError = true;
                            // skip
                        }
                    }
                }

                // jdk8
                if ((!jdk8Error) //
                        && (className.startsWith("java.time.") //
                        || className.startsWith("java.util.Optional") //
                        || className.equals("java.util.concurrent.atomic.LongAdder")
                        || className.equals("java.util.concurrent.atomic.DoubleAdder")
                )) {
                    try {
                        {
                            String[] names = new String[]{
                                    "java.time.LocalDateTime",
                                    "java.time.LocalDate",
                                    "java.time.LocalTime",
                                    "java.time.ZonedDateTime",
                                    "java.time.OffsetDateTime",
                                    "java.time.OffsetTime",
                                    "java.time.ZoneOffset",
                                    "java.time.ZoneRegion",
                                    "java.time.Period",
                                    "java.time.Duration",
                                    "java.time.Instant"
                            };
                            for (String name : names) {
                                if (name.equals(className)) {
                                    /** 如果系统支持JDK8中日期类型， 使用Jdk8DateCodec 序列化 */
                                    put(Class.forName(name), writer = Jdk8DateCodec.instance);
                                    return writer;
                                }
                            }
                        }
                        {
                            String[] names = new String[]{
                                    "java.util.Optional",
                                    "java.util.OptionalDouble",
                                    "java.util.OptionalInt",
                                    "java.util.OptionalLong"
                            };
                            for (String name : names) {
                                if (name.equals(className)) {
                                    /** 如果系统支持JDK8中可选类型， 使用OptionalCodec 序列化 */
                                    put(Class.forName(name), writer = OptionalCodec.instance);
                                    return writer;
                                }
                            }
                        }
                        {
                            String[] names = new String[]{
                                    "java.util.concurrent.atomic.LongAdder",
                                    "java.util.concurrent.atomic.DoubleAdder"
                            };
                            for (String name : names) {
                                if (name.equals(className)) {
                                    /** 如果系统支持JDK8中原子类型， 使用AdderSerializer 序列化 */
                                    put(Class.forName(name), writer = AdderSerializer.instance);
                                    return writer;
                                }
                            }
                        }
                    } catch (Throwable e) {
                        // skip
                        jdk8Error = true;
                    }
                }

                if ((!oracleJdbcError) //
                        && className.startsWith("oracle.sql.")) {
                    try {
                        String[] names = new String[] {
                                "oracle.sql.DATE",
                                "oracle.sql.TIMESTAMP"
                        };

                        for (String name : names) {
                            if (name.equals(className)) {
                                /** 如果系统支持oralcle驱动中日期类型， 使用DateCodec 序列化 */
                                put(Class.forName(name), writer = DateCodec.instance);
                                return writer;
                            }
                        }
                    } catch (Throwable e) {
                        // skip
                        oracleJdbcError = true;
                    }
                }

                if ((!springfoxError) //
                        && className.equals("springfox.documentation.spring.web.json.Json")) {
                    try {
                        /** 如果系统支持springfox-spring-web框架中Json类型， 使用SwaggerJsonSerializer 序列化 */
                        put(Class.forName("springfox.documentation.spring.web.json.Json"),
                                writer = SwaggerJsonSerializer.instance);
                        return writer;
                    } catch (ClassNotFoundException e) {
                        // skip
                        springfoxError = true;
                    }
                }

                if ((!guavaError) //
                        && className.startsWith("com.google.common.collect.")) {
                    try {
                        String[] names = new String[] {
                                "com.google.common.collect.HashMultimap",
                                "com.google.common.collect.LinkedListMultimap",
                                "com.google.common.collect.LinkedHashMultimap",
                                "com.google.common.collect.ArrayListMultimap",
                                "com.google.common.collect.TreeMultimap"
                        };

                        for (String name : names) {
                            if (name.equals(className)) {
                                /** 如果系统支持guava框架中日期类型， 使用GuavaCodec 序列化 */
                                put(Class.forName(name), writer = GuavaCodec.instance);
                                return writer;
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                        guavaError = true;
                    }
                }

                if ((!jsonnullError) && className.equals("net.sf.json.JSONNull")) {
                    try {
                        /** 如果系统支持json-lib框架中JSONNull类型， 使用MiscCodec 序列化 */
                        put(Class.forName("net.sf.json.JSONNull"), writer = MiscCodec.instance);
                        return writer;
                    } catch (ClassNotFoundException e) {
                        // skip
                        jsonnullError = true;
                    }
                }

                if ((!jodaError) && className.startsWith("org.joda.")) {
                    try {
                        String[] names = new String[] {
                                "org.joda.time.LocalDate",
                                "org.joda.time.LocalDateTime",
                                "org.joda.time.LocalTime",
                                "org.joda.time.Instant",
                                "org.joda.time.DateTime",
                                "org.joda.time.Period",
                                "org.joda.time.Duration",
                                "org.joda.time.DateTimeZone",
                                "org.joda.time.UTCDateTimeZone",
                                "org.joda.time.tz.CachedDateTimeZone",
                                "org.joda.time.tz.FixedDateTimeZone",
                        };

                        for (String name : names) {
                            if (name.equals(className)) {
                                put(Class.forName(name), writer = JodaCodec.instance);
                                return writer;
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                        jodaError = true;
                    }
                }

                Class[] interfaces = clazz.getInterfaces();
                /** 如果class只实现唯一接口，并且接口包含注解，使用AnnotationSerializer 序列化 */
                if (interfaces.length == 1 && interfaces[0].isAnnotation()) {
                    put(clazz, AnnotationSerializer.instance);
                    return AnnotationSerializer.instance;
                }

                /** 如果使用了cglib或者javassist动态代理 */
                if (TypeUtils.isProxy(clazz)) {
                    Class<?> superClazz = clazz.getSuperclass();

                    /** 通过父类型查找序列化，父类是真实的类型 */
                    ObjectSerializer superWriter = getObjectWriter(superClazz);
                    put(clazz, superWriter);
                    return superWriter;
                }

                /** 如果使用了jdk动态代理 */
                if (Proxy.isProxyClass(clazz)) {
                    Class handlerClass = null;

                    if (interfaces.length == 2) {
                        handlerClass = interfaces[1];
                    } else {
                        for (Class proxiedInterface : interfaces) {
                            if (proxiedInterface.getName().startsWith("org.springframework.aop.")) {
                                continue;
                            }
                            if (handlerClass != null) {
                                handlerClass = null; // multi-matched
                                break;
                            }
                            handlerClass = proxiedInterface;
                        }
                    }

                    if (handlerClass != null) {
                        /** 根据class实现接口类型查找序列化 */
                        ObjectSerializer superWriter = getObjectWriter(handlerClass);
                        put(clazz, superWriter);
                        return superWriter;
                    }
                }

                if (create) {
                    /** 没有精确匹配，使用通用JavaBeanSerializer 序列化(假设不启用asm) */
                    writer = createJavaBeanSerializer(clazz);
                    put(clazz, writer);
                }
            }

            if (writer == null) {
                /** 尝试在已注册缓存找到特定class的序列化实例 */
                writer = serializers.get(clazz);
            }
        }
        return writer;
    }
	
	public final ObjectSerializer get(Type key) {
	    return this.serializers.get(key);
	}

    public boolean put(Object type, Object value) {
        return put((Type)type, (ObjectSerializer)value);
    }

	public boolean put(Type type, ObjectSerializer value) {
        return this.serializers.put(type, value);
	}

    /**
     * 1.2.24
     * @param enumClasses
     */
	public void configEnumAsJavaBean(Class<? extends Enum>... enumClasses) {
        for (Class<? extends Enum> enumClass : enumClasses) {
            put(enumClass, createJavaBeanSerializer(enumClass));
        }
    }

    /**
     * for spring config support
     * @param propertyNamingStrategy
     */
    public void setPropertyNamingStrategy(PropertyNamingStrategy propertyNamingStrategy) {
        this.propertyNamingStrategy = propertyNamingStrategy;
    }

    public void clearSerializers() {
        this.serializers.clear();
        this.initSerializers();
    }
}
