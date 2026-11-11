package com.dhruva.airtribe.prism;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration public class CorsConfig implements WebMvcConfigurer {
 private final JwtAdminInterceptor auth;
 private final String[] allowedOrigins;
 public CorsConfig(JwtAdminInterceptor auth,
                   @Value("${prism.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}") String allowedOrigins){
   this.auth=auth;
   this.allowedOrigins=Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin->!origin.isBlank()).toArray(String[]::new);
 }
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(auth).addPathPatterns("/admin/**");}
 @Override public void addCorsMappings(CorsRegistry registry){registry.addMapping("/**").allowedOrigins(allowedOrigins).allowedMethods("GET","POST","PATCH","DELETE","OPTIONS").allowedHeaders("*").exposedHeaders("x-prism-provider","x-prism-model","x-prism-model-creator","x-prism-model-family","x-prism-model-hidden","x-prism-tier","x-prism-cache","x-prism-fallback","x-prism-cost-usd","x-prism-request-id","x-prism-task","x-prism-conversation-id","x-prism-verified");}
}
