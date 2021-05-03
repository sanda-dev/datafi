package dev.sanda.datafi;

import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.sanda.datafi.annotations.EntityApiSpec;
import dev.sanda.datafi.code_generator.annotated_element_specs.EntityDalSpec;
import dev.sanda.datafi.persistence.Archivable;
import dev.sanda.datafi.reflection.cached_type_info.CachedEntityTypeInfo;
import dev.sanda.datafi.reflection.runtime_services.ReflectionCache;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.persistence.*;
import javax.tools.Diagnostic;
import lombok.val;
import lombok.var;
import org.apache.commons.lang3.StringUtils;
import org.atteo.evo.inflector.English;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.springframework.aop.framework.Advised;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public class DatafiStaticUtils {

  public static String toPascalCase(String string) {
    return string.substring(0, 1).toUpperCase() + string.substring(1);
  }

  public static String toCamelCase(String string) {
    return string.substring(0, 1).toLowerCase() + string.substring(1);
  }

  public static String toPlural(String word) {
    return English.plural(word);
  }

  public static String toSingular(String word) {
    return EnglishUtils.singularOf(word);
  }

  public static void logCompilationError(
    ProcessingEnvironment processingEnvironment,
    Element element,
    String message
  ) {
    processingEnvironment
      .getMessager()
      .printMessage(
        Diagnostic.Kind.ERROR,
        message + " --> " + element.getSimpleName().toString(),
        element
      );
  }

  public static void writeToJavaFile(
    String entitySimpleName,
    String packageName,
    TypeSpec.Builder builder,
    ProcessingEnvironment processingEnvironment,
    String templateType
  ) {
    builder.addJavadoc(
      entitySimpleName +
      " " +
      templateType +
      " generated by dev.sanda @" +
      LocalDateTime.now()
    );
    final TypeSpec newClass = builder.build();
    final JavaFile javaFile = JavaFile.builder(packageName, newClass).build();

    try {
      javaFile.writeTo(System.out);
      javaFile.writeTo(processingEnvironment.getFiler());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static List<VariableElement> getFieldsOf(TypeElement entity) {
    return entity
      .getEnclosedElements()
      .stream()
      .filter(e -> e.getKind().isField())
      .map(e -> (VariableElement) e)
      .collect(Collectors.toList());
  }

  public static List<ExecutableElement> getGettersOf(TypeElement entity) {
    if (entity == null) return new ArrayList<>();
    return entity
      .getEnclosedElements()
      .stream()
      .filter(e -> e instanceof ExecutableElement)
      .map(e -> (ExecutableElement) e)
      .filter(e -> e.getSimpleName().toString().startsWith("get"))
      .collect(Collectors.toList());
  }

  public static boolean isCollectionField(
    Element element,
    ProcessingEnvironment env
  ) {
    boolean isField = element.getKind().isField();
    if (!isField) return false;
    val typeUtils = env.getTypeUtils();
    TypeMirror collection = typeUtils.erasure(
      env.getElementUtils().getTypeElement("java.util.Set").asType()
    );
    return typeUtils.isAssignable(
      typeUtils.erasure(element.asType()),
      collection
    );
  }

  public static void throwEntityNotFoundException(
    String simpleName,
    Object id
  ) {
    throw new RuntimeException("Cannot find " + simpleName + " by id: " + id);
  }

  @SuppressWarnings("unchecked")
  public static <T> List<Object> getIdList(
    Collection<T> input,
    ReflectionCache reflectionCache
  ) {
    if (input.isEmpty()) return new ArrayList<>();
    Collection<T> deproxifiedInput = (Collection<T>) input
      .stream()
      .map(DatafiStaticUtils::deProxify)
      .collect(Collectors.toSet());
    T firstItem = deproxifiedInput.iterator().next();
    final String clazzName = firstItem.getClass().getSimpleName();
    final CachedEntityTypeInfo cachedEntityTypeInfo = reflectionCache
      .getEntitiesCache()
      .get(clazzName);
    List<Object> ids = new ArrayList<>();
    deproxifiedInput.forEach(item -> ids.add(cachedEntityTypeInfo.getId(item)));
    return ids;
  }

  public static <T> PageRequest generatePageRequest(
    dev.sanda.datafi.dto.PageRequest request,
    long totalCount
  ) {
    int pageNumber, pageSize;
    if (request.getFetchAll()) {
      pageNumber = 0;
      pageSize = (int) totalCount;
    } else if (!request.isValidPagingRange()) {
      throw new IllegalArgumentException("Invalid paging range");
    } else {
      pageNumber = request.getPageNumber();
      pageSize = request.getPageSize();
    }
    if (request.getSortBy() != null) {
      return PageRequest.of(
        pageNumber,
        pageSize,
        Sort.by(request.getSortDirection(), request.getSortBy())
      );
    } else return PageRequest.of(pageNumber, pageSize);
  }

  public static void validateSortByIfNonNull(
    Class<?> clazz,
    String sortByFieldName,
    ReflectionCache reflectionCache
  ) {
    if (sortByFieldName == null) return;
    CachedEntityTypeInfo entityTypeInfo = reflectionCache
      .getEntitiesCache()
      .get(clazz.getSimpleName());
    if (
      !entityTypeInfo.getSortKeys().contains(sortByFieldName)
    ) throw new IllegalArgumentException(
      "Cannot sort by " +
      sortByFieldName +
      " as there is no such field in " +
      clazz.getName()
    );
  }

  public static String firstLowerCaseLetterOf(String str) {
    return str.substring(0, 1).toLowerCase();
  }

  public static ClassName getIdType(
    TypeElement entity,
    ProcessingEnvironment processingEnv
  ) {
    for (Element field : entity.getEnclosedElements()) {
      if (
        field.getKind() == ElementKind.FIELD &&
        (
          field.getAnnotation(Id.class) != null ||
          field.getAnnotation(EmbeddedId.class) != null
        )
      ) {
        return (ClassName) ClassName.get(field.asType());
      }
    }
    VariableElement idField;
    if (
      (idField = getPrimaryKeyJoinColumn(entity)) != null
    ) return (ClassName) ClassName.get(idField.asType());
    processingEnv
      .getMessager()
      .printMessage(
        Diagnostic.Kind.ERROR,
        "No id type found for entity " + entity.getSimpleName().toString(),
        entity
      );
    return null;
  }

  private static VariableElement getPrimaryKeyJoinColumn(TypeElement entity) {
    val superClass = (TypeElement) (
      (DeclaredType) entity.getSuperclass()
    ).asElement();
    if (
      superClass.getAnnotation(Entity.class) != null ||
      superClass.getAnnotation(Table.class) != null
    ) {
      String primaryKeyJoinColumnName;
      val primaryKeyJoinColumnAnnotation = entity.getAnnotation(
        PrimaryKeyJoinColumn.class
      );
      if (primaryKeyJoinColumnAnnotation != null) primaryKeyJoinColumnName =
        primaryKeyJoinColumnAnnotation.referencedColumnName(); else primaryKeyJoinColumnName =
        superClass
          .getEnclosedElements()
          .stream()
          .filter(
            e ->
              e.getAnnotation(Id.class) != null ||
              e.getAnnotation(EmbeddedId.class) != null
          )
          .map(idField -> idField.getSimpleName().toString())
          .findFirst()
          .orElse("id");
      return superClass
        .getEnclosedElements()
        .stream()
        .filter(
          elem ->
            elem.getKind().isField() &&
            elem.getSimpleName().toString().equals(primaryKeyJoinColumnName)
        )
        .map(idField -> (VariableElement) idField)
        .findFirst()
        .orElse(null);
    }
    return null;
  }

  public static String getBasePackage(RoundEnvironment roundEnvironment) {
    String commonPrefix = StringUtils.getCommonPrefix(
      getRootElementNames(roundEnvironment)
    );
    return commonPrefix.equals("")
      ? ""
      : commonPrefix.substring(0, commonPrefix.lastIndexOf("."));
  }

  public static String pascalCasePackageName(String packageName) {
    packageName = packageName.substring(packageName.lastIndexOf(".") + 1);
    packageName =
      Character.toUpperCase(packageName.charAt(0)) + packageName.substring(1);
    while (packageName.contains("_")) packageName =
      packageName.replaceFirst(
        "_[a-z]",
        String.valueOf(
          Character.toUpperCase(
            packageName.charAt(packageName.indexOf("_") + 1)
          )
        )
      );
    return packageName;
  }

  public static String[] getRootElementNames(
    RoundEnvironment roundEnvironment
  ) {
    return roundEnvironment
      .getRootElements()
      .stream()
      .map(el -> el.asType().toString())
      .toArray(String[]::new);
  }

  @SuppressWarnings("unchecked")
  public static List<EntityDalSpec> getEntityApiSpecs(
    RoundEnvironment roundEnvironment,
    ProcessingEnvironment processingEnv
  ) {
    Set<TypeElement> entities = new HashSet<>();
    entities.addAll(
      (Collection<? extends TypeElement>) roundEnvironment.getElementsAnnotatedWith(
        Entity.class
      )
    );
    entities.addAll(
      (Collection<? extends TypeElement>) roundEnvironment.getElementsAnnotatedWith(
        Table.class
      )
    );
    Map<TypeElement, TypeElement> extensionsMap =
      (
        (Set<TypeElement>) roundEnvironment.getElementsAnnotatedWith(
          EntityApiSpec.class
        )
      ).stream()
        .collect(
          Collectors.toMap(
            Function.identity(),
            typeElement ->
              (TypeElement) processingEnv
                .getTypeUtils()
                .asElement(typeElement.getSuperclass())
          )
        )
        .entrySet()
        .stream()
        .filter(
          entry ->
            entry.getValue().getAnnotation(Table.class) != null ||
            entry.getValue().getAnnotation(Entity.class) != null
        )
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    entities.forEach(entity -> extensionsMap.putIfAbsent(entity, null));
    Map<TypeElement, TypeElement> temp = new HashMap<>();
    for (Map.Entry<TypeElement, TypeElement> typeElementTypeElementEntry : extensionsMap.entrySet()) {
      extractReferencedEntities(
        typeElementTypeElementEntry.getKey(),
        processingEnv
      )
        .forEach(entityReference -> temp.putIfAbsent(entityReference, null));
    }
    temp.forEach(extensionsMap::putIfAbsent);
    return extensionsMap
      .entrySet()
      .stream()
      .map(entry -> new EntityDalSpec(entry.getKey(), entry.getValue()))
      .collect(Collectors.toList());
  }

  public static Set<TypeElement> extractReferencedEntities(
    TypeElement entity,
    ProcessingEnvironment processingEnv
  ) {
    return getFieldsOf(entity)
      .stream()
      .filter(DatafiStaticUtils::isEntityReference)
      .map((VariableElement field) -> entityReferenceTYpe(field, processingEnv))
      .collect(Collectors.toSet());
  }

  private static TypeElement entityReferenceTYpe(
    VariableElement field,
    ProcessingEnvironment processingEnv
  ) {
    val fieldType = processingEnv.getTypeUtils().asElement(field.asType());
    if (
      isAnnotatedAs(field, OneToOne.class) ||
      isAnnotatedAs(field, ManyToOne.class)
    ) {
      return (TypeElement) fieldType;
    } else {
      val asDeclaredType = (DeclaredType) field.asType();
      return processingEnv
        .getElementUtils()
        .getTypeElement(asDeclaredType.getTypeArguments().get(0).toString());
    }
  }

  private static boolean isAnnotatedAs(
    Element element,
    Class<? extends Annotation> annotationType
  ) {
    return element.getAnnotation(annotationType) != null;
  }

  private static final Set<Class<? extends Annotation>> JPA_RELATIONS = new HashSet<>(
    Arrays.asList(
      OneToOne.class,
      OneToMany.class,
      ManyToMany.class,
      ManyToOne.class
    )
  );

  private static boolean isEntityReference(VariableElement field) {
    return JPA_RELATIONS
      .stream()
      .anyMatch(jpaAnnotation -> field.getAnnotation(jpaAnnotation) != null);
  }

  public static String camelCaseNameOf(Element element) {
    return toCamelCase(element.getSimpleName().toString());
  }

  public static String simpleNameOf(Element element) {
    return element.getSimpleName().toString();
  }

  public static Map<TypeElement, Map<String, TypeName>> getEntitiesFieldsMap(
    Set<? extends TypeElement> entities
  ) {
    Map<TypeElement, Map<String, TypeName>> result = new HashMap<>();
    for (TypeElement entity : entities) {
      Map<String, TypeName> entityFieldsMap = new HashMap<>();
      val fields = entity
        .getEnclosedElements()
        .stream()
        .filter(e -> e.getKind().isField())
        .collect(Collectors.toSet());
      for (Element field : fields) entityFieldsMap.put(
        field.getSimpleName().toString(),
        TypeName.get(field.asType())
      );
      result.put(entity, entityFieldsMap);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public static Set<? extends TypeElement> getEntitiesSet(
    RoundEnvironment roundEnvironment
  ) {
    Set<TypeElement> entities = new HashSet<>();
    entities.addAll(
      (Collection<? extends TypeElement>) roundEnvironment.getElementsAnnotatedWith(
        Entity.class
      )
    );
    entities.addAll(
      (Collection<? extends TypeElement>) roundEnvironment.getElementsAnnotatedWith(
        Table.class
      )
    );
    return Sets.newHashSet(entities);
  }

  public static boolean isDirectlyOrIndirectlyAnnotatedAs(
    Element element,
    Class<? extends Annotation> annotationType
  ) {
    boolean isDirectlyAnnotated = element.getAnnotation(annotationType) != null;
    if (isDirectlyAnnotated) return true;
    return element
      .getAnnotationMirrors()
      .stream()
      .anyMatch(
        am ->
          am.getAnnotationType().asElement().getAnnotation(annotationType) !=
          null
      );
  }

  public static Annotation getDirectOrIndirectAnnotation(
    Element element,
    Class<? extends Annotation> annotationType
  ) {
    val directAnnotation = element.getAnnotation(annotationType);
    if (directAnnotation != null) return directAnnotation;
    return element
      .getAnnotationMirrors()
      .stream()
      .filter(
        am ->
          am.getAnnotationType().asElement().getAnnotation(annotationType) !=
          null
      )
      .map(
        am -> am.getAnnotationType().asElement().getAnnotation(annotationType)
      )
      .findFirst()
      .orElse(null);
  }

  //spring framework instantiates proxies for each autowired instance.
  //if we want the actual name of the actual bean, we need to
  //'deproxy' the instance.
  public static String extractActualName(
    Object proxyInstance,
    String classNameKeyWord
  ) {
    val interfaces = ((Advised) proxyInstance).getProxiedInterfaces();
    String actualName = "";
    for (Class<?> interface_ : interfaces) {
      if (interface_.getSimpleName().contains(classNameKeyWord)) {
        actualName = interface_.getSimpleName();
        break;
      }
    }
    int endIndex = actualName.indexOf(classNameKeyWord);
    return endIndex != -1 ? actualName.substring(0, endIndex) : null;
  }

  public static <V> Map<String, V> toServicesMap(
    List<? extends V> asList,
    String classNameKeyWord
  ) {
    return asList
      .stream()
      .collect(
        Collectors.toMap(
          item -> extractActualName(item, classNameKeyWord),
          item -> item
        )
      );
  }

  public static <T> Object getId(T input, ReflectionCache reflectionCache) {
    final Object instance = deProxify(input);
    return reflectionCache.getIdOf(
      instance.getClass().getSimpleName(),
      instance
    );
  }

  @SuppressWarnings("unchecked")
  public static <T> T deProxify(Object possibleProxy) {
    Object result = possibleProxy;
    if (possibleProxy instanceof HibernateProxy) {
      HibernateProxy hibernateProxy = (HibernateProxy) possibleProxy;
      LazyInitializer initializer = hibernateProxy.getHibernateLazyInitializer();
      result = initializer.getImplementation();
    }
    return (T) result;
  }

  public static boolean isArchivable(
    TypeElement entity,
    ProcessingEnvironment processingEnv
  ) {
    return implementsInterface(
      entity,
      processingEnv
        .getElementUtils()
        .getTypeElement(Archivable.class.getCanonicalName())
        .asType(),
      processingEnv
    );
  }

  public static boolean implementsInterface(
    TypeElement myTypeElement,
    TypeMirror desiredInterface,
    ProcessingEnvironment processingEnv
  ) {
    return processingEnv
      .getTypeUtils()
      .isAssignable(myTypeElement.asType(), desiredInterface);
  }

  public static boolean hasOneOfAnnotations(
    Element element,
    Class<? extends Annotation>... annotationTypes
  ) {
    return Arrays
      .stream(annotationTypes)
      .anyMatch(type -> element.getAnnotation(type) != null);
  }

  public static boolean hasOneOfAnnotations(
    Class<?> clazz,
    Class<? extends Annotation>... annotationTypes
  ) {
    return Arrays.stream(annotationTypes).anyMatch(clazz::isAnnotationPresent);
  }

  public static boolean hasOneOfAnnotations(
    Field field,
    Class<? extends Annotation>... annotationTypes
  ) {
    return Arrays.stream(annotationTypes).anyMatch(field::isAnnotationPresent);
  }

  public static List<String> getModelPackageNames(
    List<EntityDalSpec> entitySpecs
  ) {
    val qualifiedNames = entitySpecs
      .stream()
      .map(
        entityDalSpec ->
          entityDalSpec.getElement().getQualifiedName().toString()
      )
      .sorted(String::compareTo)
      .collect(Collectors.toList());
    var commonPrefix = StringUtils.getCommonPrefix(
      qualifiedNames.stream().toArray(String[]::new)
    );
    if (commonPrefix.endsWith(".")) commonPrefix =
      commonPrefix.substring(0, commonPrefix.lastIndexOf("."));
    if (!commonPrefix.equals("")) return Collections.singletonList(
      commonPrefix
    );
    Set<String> currentGrouping = new HashSet<>();
    Set<String> prefixes = new HashSet<>();
    for (
      int i = 0, qualifiedNamesSize = qualifiedNames.size();
      i < qualifiedNamesSize;
      i++
    ) {
      String name = qualifiedNames.get(i);
      currentGrouping.add(name);
      val commonGroupPrefix = StringUtils.getCommonPrefix(
        currentGrouping.stream().toArray(String[]::new)
      );
      if (commonGroupPrefix.equals("")) {
        currentGrouping.remove(name);
        i--;
        var newFinalPrefix = StringUtils.getCommonPrefix(
          currentGrouping.stream().toArray(String[]::new)
        );
        if (newFinalPrefix.endsWith(".")) newFinalPrefix =
          newFinalPrefix.substring(0, newFinalPrefix.lastIndexOf("."));
        prefixes.add(newFinalPrefix);
        currentGrouping = new HashSet<>();
      } else if (i + 1 >= qualifiedNamesSize && !currentGrouping.isEmpty()) {
        var newFinalPrefix = StringUtils.getCommonPrefix(
          currentGrouping.stream().toArray(String[]::new)
        );
        if (newFinalPrefix.endsWith(".")) newFinalPrefix =
          newFinalPrefix.substring(0, newFinalPrefix.lastIndexOf("."));
        prefixes.add(newFinalPrefix);
      }
    }
    return new ArrayList<>(prefixes);
  }
}
