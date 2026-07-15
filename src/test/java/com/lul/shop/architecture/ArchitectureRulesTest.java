package com.lul.shop.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.lul.shop",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    private static final String ROOT_PACKAGE = "com.lul.shop.";

    private static final Set<String> BUSINESS_MODULES = Set.of(
            "auth",
            "cart",
            "catalog",
            "notification",
            "ordering",
            "outbox",
            "payment"
    );


    @ArchTest
    static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_FRAMEWORKS_OR_OUTER_LAYERS =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "com.fasterxml.jackson..",
                            "software.amazon.awssdk..",
                            "..presentation..",
                            "..infrastructure.."
                    )
                    .because("domain code must remain independent from frameworks and outer layers");

    @ArchTest
    static final ArchRule APPLICATION_MUST_NOT_DEPEND_ON_OUTER_LAYERS =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..presentation..",
                            "..infrastructure.."
                    )
                    .because("application use cases must not depend on delivery or infrastructure details");

    @ArchTest
    static final ArchRule SHARED_MUST_NOT_DEPEND_ON_BUSINESS_MODULES =
            noClasses()
                    .that().resideInAPackage("com.lul.shop.shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.lul.shop.auth..",
                            "com.lul.shop.cart..",
                            "com.lul.shop.catalog..",
                            "com.lul.shop.notification..",
                            "com.lul.shop.ordering..",
                            "com.lul.shop.outbox..",
                            "com.lul.shop.payment.."
                    )
                    .because("shared must contain reusable technical primitives, not business-module logic");


    @ArchTest
    static final ArchRule BUSINESS_MODULES_MUST_NOT_DEPEND_ON_OTHER_MODULE_PERSISTENCE =
            classes()
                    .that().resideInAnyPackage(businessModulePackages())
                    .should(notDependOnAnotherModulePersistence())
                    .because("a business module must not access another module's persistence internals");

    @ArchTest
    static final ArchRule CROSS_MODULE_DEPENDENCIES_MUST_BE_EXPLICITLY_APPROVED =
            classes()
                    .that().resideInAnyPackage(businessModulePackages())
                    .should(useOnlyExplicitlyApprovedCrossModuleDependencies())
                    .because("cross-module calls must use reviewed application contracts");

    private static String[] businessModulePackages() {
        return BUSINESS_MODULES.stream()
                .map(module -> ROOT_PACKAGE + module + "..")
                .toArray(String[]::new);
    }

    private static ArchCondition<JavaClass> notDependOnAnotherModulePersistence() {
        return new ArchCondition<>(
                "not depend on another business module's persistence internals"
        ) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String sourceModule = moduleOf(origin);

                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);

                    if (!sourceModule.equals(targetModule)
                            && BUSINESS_MODULES.contains(targetModule)
                            && isPersistenceInternal(target)) {
                        events.add(SimpleConditionEvent.violated(
                                dependency,
                                dependency.getDescription()
                                        + " crosses the persistence boundary from "
                                        + sourceModule
                                        + " to "
                                        + targetModule
                        ));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> useOnlyExplicitlyApprovedCrossModuleDependencies() {
        return new ArchCondition<>("use only explicitly approved cross-module dependencies") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();

                    if (isCrossBusinessModuleDependency(origin, target)
                            && !isApprovedCrossModuleDependency(origin, target)) {
                        events.add(SimpleConditionEvent.violated(
                                dependency,
                                "Unapproved cross-module dependency: "
                                        + dependency.getDescription()
                        ));
                    }
                }
            }
        };
    }

    private static boolean isCrossBusinessModuleDependency(
            JavaClass origin,
            JavaClass target
    ) {
        String originModule = moduleOf(origin);
        String targetModule = moduleOf(target);

        return BUSINESS_MODULES.contains(originModule)
                && BUSINESS_MODULES.contains(targetModule)
                && !originModule.equals(targetModule);
    }

    private static boolean isApprovedCrossModuleDependency(
            JavaClass origin,
            JavaClass target
    ) {
        return (residesIn(origin, "com.lul.shop.cart.infrastructure.catalog")
                && residesIn(target, "com.lul.shop.catalog.application"))
                || (residesIn(origin, "com.lul.shop.ordering.infrastructure.cart")
                && residesIn(target, "com.lul.shop.cart.application"))
                || (residesIn(origin, "com.lul.shop.ordering.infrastructure.catalog")
                && residesIn(target, "com.lul.shop.catalog.application"))
                || (residesIn(origin, "com.lul.shop.payment.infrastructure.ordering")
                && residesIn(target, "com.lul.shop.ordering.application"))
                || (residesIn(origin, "com.lul.shop.payment.infrastructure.ordering")
                && target.getName().equals("com.lul.shop.ordering.domain.OrderStatus"))
                || (origin.getName().equals("com.lul.shop.payment.application.PaymentService")
                && target.getName().equals("com.lul.shop.outbox.application.OutboxService"));
    }

    private static boolean residesIn(JavaClass javaClass, String packageName) {
        return javaClass.getPackageName().equals(packageName)
                || javaClass.getPackageName().startsWith(packageName + ".");
    }

    private static String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();

        if (!packageName.startsWith(ROOT_PACKAGE)) {
            return "";
        }

        String relativePackage = packageName.substring(ROOT_PACKAGE.length());
        int separatorIndex = relativePackage.indexOf('.');

        return separatorIndex < 0
                ? relativePackage
                : relativePackage.substring(0, separatorIndex);
    }

    private static boolean isPersistenceInternal(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();

        return packageName.contains(".infrastructure.persistence.entity")
                || packageName.contains(".infrastructure.persistence.repository");
    }
}