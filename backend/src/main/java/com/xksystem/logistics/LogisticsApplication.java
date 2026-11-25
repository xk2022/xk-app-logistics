package com.xksystem.logistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import com.xksystem.logistics.upms.OfflineAccounts;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
public class LogisticsApplication {
    public static void main(String[] args) {
        String command=null;
        for (String arg : args) {
            if (arg.startsWith("--upms.command=")) {
                if (command != null) throw new IllegalArgumentException("Only one offline account command is allowed");
                command=arg.substring("--upms.command=".length());
            }
        }
        if (command == null) {
            SpringApplication.run(LogisticsApplication.class, args);
        } else {
            if (!command.equals("init") && !command.equals("recover")) throw new IllegalArgumentException("Use --upms.command=init or recover");
            if (System.console() == null) throw new IllegalStateException("Offline account operations require a human interactive terminal");
            var application=new SpringApplication(LogisticsApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context=application.run(args)) {
                context.getBean(OfflineAccounts.class).run(command, System.console());
            }
        }
    }
}
