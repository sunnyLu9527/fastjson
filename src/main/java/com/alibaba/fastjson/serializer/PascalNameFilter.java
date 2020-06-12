package com.alibaba.fastjson.serializer;

/***
* @Param
* @description 属性名称首字母大写
* @author luming
* @date 2020/6/12 11:15
* @return
* @throws
*/
public class PascalNameFilter implements NameFilter {

    public String process(Object source, String name, Object value) {
        if (name == null || name.length() == 0) {
            return name;
        }
        
        char[] chars = name.toCharArray();
        chars[0]= Character.toUpperCase(chars[0]);
        
        String pascalName = new String(chars);
        return pascalName;
    }
}
