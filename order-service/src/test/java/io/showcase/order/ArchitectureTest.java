package io.showcase.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * ARCHITECTURE AS EXECUTABLE TESTS (ArchUnit). The rules below used to be wiki pages that nobody read.
 * Now a violating pull request FAILS THE BUILD, and code review can focus on design instead of
 * policing package imports.
 */
@AnalyzeClasses(packages = "io.showcase.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The domain is the core: it must not know about web, messaging, persistence adapters or integrations. */
    @ArchTest
    static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..order.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..order.web..", "..order.messaging..", "..order.persistence..", "..order.integration..",
                    "..order.graphql..", "org.springframework.web..", "org.springframework.kafka..",
                    "org.springframework.amqp..", "io.grpc..")
            .because("business rules must not depend on delivery or infrastructure details");

    /** Only the transport layer talks HTTP. Application services must not depend on controllers. */
    @ArchTest
    static final ArchRule nothingDependsOnWeb = noClasses().that().resideOutsideOfPackages("..order.web..", "..order.security..")
            .should().dependOnClassesThat().resideInAPackage("..order.web..");

    /** Adapters (gRPC, HTTP clients) are reached ONLY through ports. */
    @ArchTest
    static final ArchRule integrationsAreAccessedThroughPorts = noClasses().that().resideInAPackage("..order.application..")
            .should().dependOnClassesThat().resideInAPackage("..order.integration..");

    @ArchTest
    static final ArchRule noPackageCycles = slices().matching("io.showcase.order.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cycles make modules impossible to understand, test or extract");

    /** Constructor injection only: field injection hides dependencies and breaks immutability and testing. */
    @ArchTest
    static final ArchRule noFieldInjection = fields().should().notBeAnnotatedWith(Autowired.class);

    /** Transactions belong to the application layer, never to controllers. */
    @ArchTest
    static final ArchRule noTransactionalControllers = noClasses().that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule servicesLiveInApplicationOrMessaging = classes().that().areAnnotatedWith(Service.class)
            .should().resideInAnyPackage("..order.application..", "..order.messaging..");
}
