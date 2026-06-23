package org.nsponline.calendar.config;

import org.nsponline.calendar.utils.SessionData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }

    @Bean
    public JavaMailSender javaMailSender() {
        Properties creds = loadCredentials();

        String host     = creds.getProperty("smtpHost", "localhost");
        String username = creds.getProperty("hostEmailUser", "");
        String password = creds.getProperty("hostEmailPassword", "");
        int    port     = Integer.parseInt(creds.getProperty("smtpPort", "587"));

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return mailSender;
    }

    private Properties loadCredentials() {
        String path = System.getProperty("credentials.path", SessionData.CREDENTIALS_PROPERTIES_FILE);
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (Exception e) {
            // credentials file unavailable — host defaults to localhost
        }
        return props;
    }
}
