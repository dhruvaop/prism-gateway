package com.dhruva.airtribe.prism;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration public class CorsConfig implements WebMvcConfigurer {
 private final JwtAdminInterceptor auth;
 public CorsConfig(JwtAdminInterceptor auth){this.auth=auth;}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(auth).addPathPatterns("/admin/**");}
 @Override public void addCorsMappings(CorsRegistry registry){registry.addMapping("/**").allowedOrigins("http://localhost:5173","http://127.0.0.1:5173").allowedMethods("GET","POST","OPTIONS").allowedHeaders("*");}
}
