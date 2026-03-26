package cn.edu.seig.vibemusic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching   // 开启 Spring Boot 基于注解的缓存管理支持
@EnableScheduling // 开启定时任务支持
@SpringBootApplication
public class VibeMusicServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VibeMusicServerApplication.class, args);
    }

}
