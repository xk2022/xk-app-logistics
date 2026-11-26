package com.xksystem.logistics.tms.completion;
import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class UnavailableCompletionConfiguration {
    @Bean public CompletionPolicy completionPolicy(){return new CompletionPolicy(){
        public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}
        public Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver){return Optional.empty();}
    };}
}
