package com.cosmetic.scm.common;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 请求里的文字去掉不可见的控制字符（保留制表符和换行）。从别的系统复制过来时常带这些字符，
 * 存进去页面上看不出来，却会让按名称搜索找不到、和别的系统对不上。
 */
@Configuration
public class JacksonConfig {

    static String clean(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean bad = (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F;
            if (bad && out == null) {
                out = new StringBuilder(s.length()).append(s, 0, i);
            } else if (!bad && out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    @Bean
    SimpleModule cleanStringsModule() {
        SimpleModule m = new SimpleModule("clean-strings");
        m.addDeserializer(String.class, new StringDeserializer() {
            @Override
            public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return clean(super.deserialize(p, ctxt));
            }
        });
        return m;
    }
}
