package top.sshh.bililiverecoder.config.db;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 先完成数据库兼容检查，再让 Hibernate 处理表结构 */
@Component
public class DatabaseMigrationJpaDependencyPostProcessor implements BeanFactoryPostProcessor {
    private static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";
    private static final String MIGRATION_INITIALIZER = "databaseMigrationInitializer";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBeanDefinition(ENTITY_MANAGER_FACTORY)) {
            throw new BeanCreationException(ENTITY_MANAGER_FACTORY,
                    "无法建立数据库迁移与 JPA 的启动依赖");
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(ENTITY_MANAGER_FACTORY);
        String[] existing = definition.getDependsOn();
        if (existing != null && Arrays.stream(existing).anyMatch(MIGRATION_INITIALIZER::equals)) return;
        List<String> dependencies = new ArrayList<>();
        if (existing != null) dependencies.addAll(Arrays.asList(existing));
        dependencies.add(MIGRATION_INITIALIZER);
        definition.setDependsOn(dependencies.toArray(String[]::new));
    }
}
