package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnavailableExceptionConfiguration {
    @Bean public ExceptionPolicy exceptionPolicy(){return new ExceptionPolicy(){
        public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}
        public boolean allows(Action action,Context context){return false;}
        public Optional<ExecutionEvidencePolicy.Requirements> requirements(Context context){return Optional.empty();}
        public boolean managerCanRead(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){return false;}
    };}
}
