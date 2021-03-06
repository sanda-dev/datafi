package dev.sanda.datafi.reflection.cached_type_info;

import static dev.sanda.datafi.DatafiStaticUtils.hasOneOfAnnotations;
import static dev.sanda.datafi.reflection.runtime_services.ReflectionCache.getClassFields;

import dev.sanda.datafi.annotations.attributes.NonApiUpdatable;
import dev.sanda.datafi.annotations.attributes.NonApiUpdatables;
import dev.sanda.datafi.annotations.attributes.NonNullable;
import dev.sanda.datafi.persistence.Archivable;
import dev.sanda.datafi.reflection.relationship_synchronization.EntityRelationshipSyncronizer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import javax.persistence.*;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.val;

@lombok.Getter
public class CachedEntityTypeInfo {

  private Field idField;

  private Map<String, Field> backpointers;
  private Map<String, CachedElementCollectionField> elementCollections;
  private Map<String, CachedMapElementCollectionField> mapElementCollections;

  @Getter
  private EntityRelationshipSyncronizer relationshipSyncronizer;

  private Class<?> clazz;
  private Object defaultInstance;
  private Map<String, CachedEntityField> fields;
  private HashSet<String> sortKeys;
  private List<Field> cascadeUpdatableFields;
  private Map<String, Method> publicMethods;
  private List<String> searchFields;
  private boolean isArchivable = false;

  public CachedEntityTypeInfo(
    Class<?> clazz,
    Collection<Field> fields,
    Collection<Method> publicMethods,
    EntityRelationshipSyncronizer relationshipSyncronizer
  ) {
    this.relationshipSyncronizer = relationshipSyncronizer;
    sortKeys = new HashSet<>();
    elementCollections = new HashMap<>();
    backpointers = new HashMap<>();
    val blacklistedBackpointers = new HashSet<Class>();
    mapElementCollections = new HashMap<>();
    this.clazz = clazz;
    if (Archivable.class.isAssignableFrom(clazz)) isArchivable = true;
    this.fields = new HashMap<>();
    fields.forEach(
      field -> {
        field.setAccessible(true);
        boolean isCollectionOrMap = isCollectionOrMap(field);
        boolean isNonApiUpdatable = isNonApiUpdatable(field);
        boolean isNonNullable = isNonNullableField(field);
        val fieldName = field.getName();
        if (isEmbeddedOrForeignKey(field)) addNestedSortKeys(
          field,
          fieldName + ".",
          new Stack<>()
        );
        this.fields.put(
            fieldName,
            new CachedEntityField(
              field,
              isCollectionOrMap,
              isNonApiUpdatable,
              isNonNullable
            )
          );
        sortKeys.add(fieldName);
        if (
          field.isAnnotationPresent(Id.class) ||
          field.isAnnotationPresent(EmbeddedId.class)
        ) {
          this.idField = field;
        }
        if (field.isAnnotationPresent(ElementCollection.class)) {
          val fieldType = field.getType();
          if (Map.class.isAssignableFrom(fieldType)) {
            mapElementCollections.put(
              fieldName,
              new CachedMapElementCollectionField(field)
            );
          } else if (Collection.class.isAssignableFrom(fieldType)) {
            elementCollections.put(
              fieldName,
              new CachedElementCollectionField(field)
            );
          }
        }
        if (
          field.isAnnotationPresent(ManyToOne.class) &&
          !blacklistedBackpointers.contains(field.getType())
        ) {
          val fieldClazz = field.getType();
          val fieldClazzName = fieldClazz.getSimpleName();
          if (backpointers.containsKey(fieldClazzName)) {
            backpointers.remove(fieldClazzName);
            blacklistedBackpointers.add(fieldClazz);
          } else {
            backpointers.put(fieldClazzName, field);
          }
        }
      }
    );
    this.publicMethods = new HashMap<>();
    publicMethods.forEach(
      publicMethod ->
        this.publicMethods.put(publicMethod.getName(), publicMethod)
    );
    this.defaultInstance = genDefaultInstance(clazz);
    setCascadeUpdatableFields();
  }

  private boolean isEmbeddedOrForeignKey(Field field) {
    return hasOneOfAnnotations(
      field,
      EmbeddedId.class,
      Embedded.class,
      ManyToOne.class,
      OneToOne.class
    );
  }

  @SneakyThrows
  public String toFlatJson(Object instance) {
    val builder = new StringBuilder("{");
    fields
      .entrySet()
      .stream()
      .filter(
        entry ->
          entry.getValue().getJsonValue(instance) != null &&
          !entry.getValue().isCollectionOrMap()
      )
      .map(
        entry ->
          "\"" +
          entry.getKey() +
          "\": " +
          entry.getValue().getJsonValue(instance).toString()
      )
      .forEach(
        jsonKeyValuePair -> builder.append(jsonKeyValuePair).append(",")
      );
    builder.setLength(builder.length() - 1);
    builder.append("}");
    return builder.toString();
  }

  private boolean isCollectionOrMap(Field field) {
    return (
      Iterable.class.isAssignableFrom(field.getType()) ||
      Map.class.isAssignableFrom(field.getType())
    );
  }

  private void addNestedSortKeys(
    Field currentRoot,
    String currentPrefix,
    Stack<Class<?>> typesSoFar
  ) {
    val currentRootType = currentRoot.getType();
    if (typesSoFar.contains(currentRootType)) return;
    typesSoFar.push(currentRootType);
    getClassFields(currentRootType)
      .forEach(
        field -> {
          if (isEmbeddedOrForeignKey(field)) addNestedSortKeys(
            field,
            currentPrefix + field.getName() + ".",
            typesSoFar
          );
          val fieldName = currentPrefix + field.getName();
          sortKeys.add(fieldName);
        }
      );
    typesSoFar.pop();
  }

  private boolean isNonApiUpdatable(Field field) {
    return (
      field.isAnnotationPresent(NonApiUpdatable.class) ||
      isInNonCascadeUpdatables(field) ||
      field.isAnnotationPresent(Id.class) ||
      field.isAnnotationPresent(EmbeddedId.class) ||
      Iterable.class.isAssignableFrom(field.getType()) ||
      field.isAnnotationPresent(ElementCollection.class) ||
      field.isAnnotationPresent(CollectionTable.class) ||
      field.getType().equals(Map.class)
    );
  }

  private boolean isInNonCascadeUpdatables(Field field) {
    NonApiUpdatables nonApiUpdatables = clazz.getAnnotation(
      NonApiUpdatables.class
    );
    if (nonApiUpdatables != null) {
      for (String fieldName : nonApiUpdatables.value()) {
        if (fieldName.equals(field.getName())) return true;
      }
    }
    return false;
  }

  private boolean isNonNullableField(Field field) {
    return (
      field.isAnnotationPresent(NonNullable.class) ||
      (
        field.isAnnotationPresent(Column.class) &&
        !field.getAnnotation(Column.class).nullable()
      ) ||
      (
        field.isAnnotationPresent(OneToOne.class) &&
        !field.getAnnotation(OneToOne.class).optional()
      ) ||
      (
        field.isAnnotationPresent(ManyToOne.class) &&
        !field.getAnnotation(ManyToOne.class).optional()
      )
    );
  }

  public static Object genDefaultInstance(Class<?> clazz) {
    Constructor[] cons = clazz.getDeclaredConstructors();
    try {
      for (Constructor constructor : cons) {
        if (constructor.getParameterCount() == 0) {
          constructor.setAccessible(true);
          return constructor.newInstance();
        }
      }
      throw new RuntimeException(
        "No default constructor found for " + clazz.getSimpleName()
      );
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void setCascadeUpdatableFields() {
    this.cascadeUpdatableFields = new ArrayList<>();
    fields
      .values()
      .forEach(
        _field -> {
          if (!_field.isNonApiUpdatable()) cascadeUpdatableFields.add(
            _field.getField()
          );
        }
      );
  }

  public Object getId(Object instance) {
    try {
      return this.idField.get(instance);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public void addAllToElementCollection(
    String fieldName,
    Object instance,
    Collection<Object> toAdd
  ) {}
}
