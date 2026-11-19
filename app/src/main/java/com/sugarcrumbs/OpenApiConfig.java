package com.sugarcrumbs;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sugarcrumbs.common.web.error.ApiError;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;

import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes {@code /v3/api-docs} complete enough to generate a typed client from (the SPA runs
 * {@code openapi-typescript} on it). springdoc infers paths, parameters and bodies by itself; this adds
 * what it can't see:
 * <ul>
 *   <li><b>Required and nullable response fields.</b> Every response record always serializes every
 *       component, so they are all {@code required}; the ones marked JSpecify {@code @Nullable} are
 *       {@code null}-able. Without this every field generates as optional.</li>
 *   <li><b>Error responses</b> referencing {@link ApiError}: {@code GlobalExceptionHandler} returns
 *       {@code ResponseEntity}s, which springdoc can't read statuses from.</li>
 *   <li><b>The bearer scheme</b>, required on every operation that {@link ApiRoutes#PUBLIC} doesn't
 *       open, so new controllers are documented correctly without annotations.</li>
 * </ul>
 * Lives in {@code app} like {@link SecurityConfig}: it describes the whole deployable, and keeps
 * springdoc out of the business modules. {@code OpenApiSpecTests} guards all of it.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    private static final String API_ERROR = "ApiError";
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

    @Bean
    OpenApiCustomizer bakerOpenApiCustomizer(@Value("${app.api.version}") String version) {
        return openApi -> {
            openApi.info(new Info()
                    .title("SugarCrumbs API")
                    .version(version)
                    .description("""
                            The bakery's storefront (`/api/public/**`, no token) and the Owner's dashboard \
                            (everything else, bearer access token from `POST /api/auth/login`). Every error \
                            has the `ApiError` body."""));
            Components components = openApi.getComponents() != null ? openApi.getComponents() : new Components();
            openApi.components(components);
            components.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("The Owner's access token (15 minutes). Renew it with `POST /api/auth/refresh`."));
            ModelConverters.getInstance(true).readAll(new AnnotatedType(ApiError.class))
                    .forEach(components::addSchemas);

            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                        documentSecurityAndErrors(HttpMethod.valueOf(method.name()), path, operation)));
            }
            markResponseFieldsRequired(openApi);
        };
    }

    private static void documentSecurityAndErrors(HttpMethod method, String path, Operation operation) {
        boolean ownerOnly = !ApiRoutes.isPublic(method, path);
        if (ownerOnly) {
            operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
        }
        if (operation.getResponses() == null) {
            operation.responses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        boolean hasInput = operation.getRequestBody() != null
                || (operation.getParameters() != null && !operation.getParameters().isEmpty());
        if (hasInput) {
            addError(responses, HttpStatus.BAD_REQUEST,
                    "Invalid input: validation (`fieldErrors` lists the fields), a malformed value, or a business rule");
        }
        // Login and refresh are public, but answer bad credentials or a bad refresh token with a 401.
        if (ownerOnly || path.startsWith("/api/auth/")) {
            addError(responses, HttpStatus.UNAUTHORIZED, "No valid access token, or bad credentials");
        }
        if (ownerOnly) {
            addError(responses, HttpStatus.FORBIDDEN, "The token doesn't carry the Owner role");
        }
        if (path.contains("{")) {
            addError(responses, HttpStatus.NOT_FOUND, "Nothing with that id, or an unknown or expired token");
        }
        if (method != HttpMethod.GET) {
            addError(responses, HttpStatus.CONFLICT,
                    "Conflicts with the current state (e.g. slot full, sold out, a duplicate name), "
                            + "or another request changed the same data first; reload and retry");
        }
        if (ApiRoutes.isRateLimited(method, path)) {
            addError(responses, HttpStatus.TOO_MANY_REQUESTS, "Rate limit for this client IP reached")
                    .addHeaderObject(HttpHeaders.RETRY_AFTER, new Header()
                            .description("Seconds to wait before retrying")
                            .schema(new JsonSchema().types(Set.of("integer"))));
        }
        addError(responses, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }

    private static ApiResponse addError(ApiResponses responses, HttpStatus status, String description) {
        return responses.computeIfAbsent(String.valueOf(status.value()), code -> new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON,
                        new MediaType().schema(new JsonSchema().$ref(SCHEMA_REF_PREFIX + API_ERROR)))));
    }

    // ------------------------------------------------------------------------------------------------
    // Required and nullable response fields
    // ------------------------------------------------------------------------------------------------

    /**
     * Only schemas used purely in responses: a schema also used as a request body (e.g. {@code AddressDto})
     * keeps its optional fields, which is what a client may leave out when sending it. Only our own records
     * are touched; Spring Data's {@code Page*} schemas are left as springdoc describes them.
     */
    private static void markResponseFieldsRequired(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        if (schemas == null || openApi.getPaths() == null) {
            return;
        }
        Set<String> responseRoots = new HashSet<>();
        Set<String> requestRoots = new HashSet<>();
        openApi.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .forEach(operation -> {
                    if (operation.getResponses() != null) {
                        operation.getResponses().values().forEach(response -> collectRefs(response.getContent(), responseRoots));
                    }
                    if (operation.getRequestBody() != null) {
                        collectRefs(operation.getRequestBody().getContent(), requestRoots);
                    }
                    if (operation.getParameters() != null) {
                        operation.getParameters().stream().map(Parameter::getSchema).forEach(s -> collectRefs(s, requestRoots));
                    }
                });
        Set<String> responseOnly = reachable(responseRoots, schemas);
        responseOnly.removeAll(reachable(requestRoots, schemas));

        Map<String, List<Class<?>>> records = ourRecordsBySimpleName();
        for (String name : responseOnly) {
            List<Class<?>> candidates = records.getOrDefault(name, List.of());
            if (candidates.size() > 1) {
                throw new IllegalStateException("Two response records are both called " + name + ": " + candidates);
            }
            if (!candidates.isEmpty()) {
                markRequired(schemas.get(name), candidates.getFirst());
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void markRequired(Schema<?> schema, Class<?> record) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        Map<String, RecordComponent> components = jsonComponents(record);
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            RecordComponent component = components.get(property.getKey());
            if (component == null) {
                continue;
            }
            required.add(property.getKey());
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                property.setValue(nullable(property.getValue()));
            }
        }
        schema.setRequired(required);
    }

    /**
     * {@code type: [T, "null"]} for a plain type. A {@code $ref} can't take a type, and an {@code enum}
     * wouldn't accept {@code null} as a value, so those become {@code anyOf: [T, {type: "null"}]}.
     */
    @SuppressWarnings("rawtypes")
    private static Schema nullable(Schema property) {
        Set<String> types = property.getTypes() != null ? new LinkedHashSet<>(property.getTypes())
                : property.getType() != null ? new LinkedHashSet<>(Set.of(property.getType())) : null;
        if (property.get$ref() != null || property.getEnum() != null || types == null) {
            return new JsonSchema().anyOf(List.of(property, new JsonSchema().types(Set.of("null"))));
        }
        types.add("null");
        property.setTypes(types);
        return property;
    }

    /** The record's JSON properties by name, with {@code @JsonUnwrapped} components' properties inlined. */
    private static Map<String, RecordComponent> jsonComponents(Class<?> record) {
        Map<String, RecordComponent> byName = new LinkedHashMap<>();
        for (RecordComponent component : record.getRecordComponents()) {
            if (component.getAccessor().isAnnotationPresent(JsonUnwrapped.class) && component.getType().isRecord()) {
                byName.putAll(jsonComponents(component.getType()));
            } else {
                byName.put(component.getName(), component);
            }
        }
        return byName;
    }

    /** springdoc names a schema after the class's simple name, nested classes included. */
    private static Map<String, List<Class<?>>> ourRecordsBySimpleName() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) ->
                Record.class.getName().equals(reader.getClassMetadata().getSuperClassName()));
        Map<String, List<Class<?>>> bySimpleName = new HashMap<>();
        for (var candidate : scanner.findCandidateComponents(BakerApplication.class.getPackageName())) {
            Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), OpenApiConfig.class.getClassLoader());
            bySimpleName.computeIfAbsent(type.getSimpleName(), name -> new ArrayList<>()).add(type);
        }
        return bySimpleName;
    }

    private static Set<String> reachable(Collection<String> roots, Map<String, Schema> schemas) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (seen.add(name) && schemas.containsKey(name)) {
                Set<String> refs = new HashSet<>();
                collectRefs(schemas.get(name), refs);
                pending.addAll(refs);
            }
        }
        return seen;
    }

    private static void collectRefs(@Nullable Content content, Set<String> into) {
        if (content != null) {
            content.values().forEach(mediaType -> collectRefs(mediaType.getSchema(), into));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectRefs(@Nullable Schema schema, Set<String> into) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            into.add(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
        }
        collectRefs(schema.getItems(), into);
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            collectRefs(additional, into);
        }
        for (List<Schema> composed : new List[]{schema.getAllOf(), schema.getAnyOf(), schema.getOneOf()}) {
            if (composed != null) {
                composed.forEach(part -> collectRefs(part, into));
            }
        }
        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).values().forEach(property -> collectRefs(property, into));
        }
    }
}
