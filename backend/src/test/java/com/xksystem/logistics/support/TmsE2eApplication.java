package com.xksystem.logistics.support;

import com.xksystem.logistics.LogisticsApplication;
import com.xksystem.logistics.tms.DispatchEligibilityPolicy;
import com.xksystem.logistics.tms.evidence.EvidenceStorage;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.exceptions.ExceptionPolicy;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;

/** Explicit external test-classpath launcher; never packaged in the production JAR. */
public final class TmsE2eApplication {
    private TmsE2eApplication() {}

    public static void main(String[] args) {
        for (String argument : args) {
            if (argument.startsWith("--upms.command=")) {
                throw new IllegalArgumentException("The isolated TMS browser launcher does not run account commands");
            }
        }
        createApplication().run(args);
    }

    /** Keeps Boot's classpath-provided initializers, including test-component exclusion when present. */
    public static SpringApplication createApplication() {
        var application = new SpringApplication(LogisticsApplication.class);
        application.addInitializers(context -> {
            if (!(context instanceof BeanDefinitionRegistry registry)) {
                throw new IllegalStateException("Synthetic dispatch launcher requires an explicit bean registry");
            }
            var definition = new RootBeanDefinition(DispatchEligibilityPolicy.class);
            definition.setPrimary(true);
            definition.setInstanceSupplier(() -> {
                try {
                    return SyntheticDispatchPolicy.create(context.getBean(DataSource.class));
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("Unable to verify the isolated synthetic dispatch database", failure);
                }
            });
            registry.registerBeanDefinition("syntheticDispatchEligibilityPolicy", definition);
            var evidencePolicy=new RootBeanDefinition(ExecutionEvidencePolicy.class);evidencePolicy.setPrimary(true);
            evidencePolicy.setInstanceSupplier(()->{
                try{return SyntheticEvidencePolicy.create(context.getBean(DataSource.class));}
                catch(java.sql.SQLException failure){throw new IllegalStateException("Unable to verify isolated evidence policy database",failure);}
            });
            registry.registerBeanDefinition("syntheticExecutionEvidencePolicy",evidencePolicy);
            var evidenceStorage=new RootBeanDefinition(EvidenceStorage.class);evidenceStorage.setPrimary(true);
            evidenceStorage.setInstanceSupplier(()->{
                try{return SyntheticEvidenceStorage.create(context.getBean(DataSource.class));}
                catch(java.sql.SQLException failure){throw new IllegalStateException("Unable to verify isolated private storage database",failure);}
            });
            registry.registerBeanDefinition("syntheticEvidenceStorage",evidenceStorage);
            var exceptionPolicy=new RootBeanDefinition(ExceptionPolicy.class);exceptionPolicy.setPrimary(true);
            exceptionPolicy.setInstanceSupplier(()->{
                try{return SyntheticExceptionPolicy.create(context.getBean(DataSource.class));}
                catch(java.sql.SQLException failure){throw new IllegalStateException("Unable to verify isolated exception policy database",failure);}
            });
            registry.registerBeanDefinition("syntheticExceptionPolicy",exceptionPolicy);
        });
        return application;
    }
}
