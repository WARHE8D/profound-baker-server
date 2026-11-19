package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPA generates its API types from {@code /v3/api-docs} (openapi-typescript), so the spec is a contract:
 * response fields required (and nullable only where they can be null), every error documented with the
 * {@code ApiError} body, the bearer scheme on exactly the Owner's routes, and JSON media types
 * ({@link OpenApiConfig}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecTests extends AbstractIntegrationTest {

    /** The routes {@code SecurityConfig} opens, as spec paths. Everything else must require the bearer token. */
    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "GET /.well-known/jwks.json",
            "GET /api/public/catalogue/items",
            "GET /api/public/catalogue/items/{id}",
            "GET /api/public/catalogue/categories",
            "GET /api/public/availability",
            "GET /api/public/bakery",
            "POST /api/public/orders",
            "GET /api/public/orders/by-token/{token}",
            "POST /api/public/orders/by-token/{token}/cancel");

    private static JsonNode spec;

    @BeforeAll
    static void fetchSpec(@LocalServerPort int port) {
        spec = RestClient.create("http://localhost:" + port).get().uri("/v3/api-docs").retrieve().body(JsonNode.class);
    }

    @Test
    void hasARealTitleAndVersion() {
        assertThat(spec.path("info").path("title").asString()).isEqualTo("SugarCrumbs API");
        assertThat(spec.path("info").path("version").asString()).isNotBlank().isNotEqualTo("v0");
    }

    @Test
    void everyResponseIsJson() {
        forEachOperation((operation, node) -> node.path("responses").properties().forEach(response ->
                assertThat(response.getValue().path("content").propertyNames())
                        .as("%s %s", operation, response.getKey())
                        .allMatch("application/json"::equals)));
    }

    @Test
    void bearerSchemeIsRequiredOnExactlyTheOwnerOperations() {
        JsonNode scheme = spec.path("components").path("securitySchemes").path(OpenApiConfig.BEARER_AUTH);
        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");

        List<String> publicOperations = new ArrayList<>();
        forEachOperation((operation, node) -> {
            boolean secured = node.path("security").toString().contains(OpenApiConfig.BEARER_AUTH);
            if (!secured) {
                publicOperations.add(operation);
            }
        });
        assertThat(publicOperations).containsExactlyInAnyOrderElementsOf(PUBLIC_OPERATIONS);
    }

    @Test
    void apiErrorIsDocumentedWithTypedFieldErrors() {
        JsonNode apiError = schema("ApiError");
        assertThat(strings(apiError.path("required")))
                .containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path", "fieldErrors");
        assertThat(types(apiError.path("properties").path("fieldErrors"))).containsExactlyInAnyOrder("array", "null");
        assertThat(apiError.path("properties").path("fieldErrors").path("items").path("$ref").asString())
                .endsWith("/FieldError");
        assertThat(strings(schema("FieldError").path("required"))).containsExactlyInAnyOrder("field", "message");
    }

    @Test
    void everyOperationDocumentsItsErrors() {
        forEachOperation((operation, node) -> {
            JsonNode responses = node.path("responses");
            boolean secured = !PUBLIC_OPERATIONS.contains(operation);
            List<String> expected = new ArrayList<>(List.of("500"));
            if (secured) {
                expected.addAll(List.of("401", "403"));
            }
            if (operation.contains("{")) {
                expected.add("404");
            }
            if (!operation.startsWith("GET ")) {
                expected.add("409");
            }
            assertThat(responses.propertyNames()).as(operation).containsAll(expected);
            for (String status : expected) {
                assertThat(responses.path(status).path("content").path("application/json").path("schema")
                        .path("$ref").asString()).as("%s %s", operation, status).endsWith("/ApiError");
            }
        });
    }

    @Test
    void rateLimitedOperationsDocument429WithRetryAfter() {
        for (String operation : List.of("POST /api/auth/login", "POST /api/public/orders",
                "GET /api/public/orders/by-token/{token}", "POST /api/public/orders/by-token/{token}/cancel")) {
            JsonNode tooMany = operation(operation).path("responses").path("429");
            assertThat(tooMany.path("headers").has("Retry-After")).as(operation).isTrue();
        }
        assertThat(operation("GET /api/public/catalogue/items").path("responses").has("429")).isFalse();
    }

    @Test
    void successStatusesMatchWhatTheControllersReturn() {
        assertThat(operation("POST /api/catalogue/items").path("responses").has("201")).isTrue();
        assertThat(operation("POST /api/public/orders").path("responses").has("201")).isTrue();
        assertThat(operation("DELETE /api/catalogue/items/{id}").path("responses").has("204")).isTrue();
        assertThat(operation("POST /api/auth/logout").path("responses").has("204")).isTrue();
    }

    @Test
    void everyResponseRecordListsItsFieldsAsRequired() {
        spec.path("components").path("schemas").properties().stream()
                .filter(entry -> entry.getKey().endsWith("Response") && !entry.getKey().startsWith("Page"))
                .forEach(entry -> assertThat(strings(entry.getValue().path("required")))
                        .as(entry.getKey())
                        .containsExactlyInAnyOrderElementsOf(entry.getValue().path("properties").propertyNames()));
    }

    @Test
    void onlyFieldsThatCanBeNullAreNullable() {
        JsonNode item = schema("ItemResponse").path("properties");
        assertThat(types(item.path("id"))).containsExactly("string");
        assertThat(types(item.path("available"))).containsExactly("boolean");
        assertThat(types(item.path("stockQuantity"))).containsExactlyInAnyOrder("integer", "null");
        assertThat(types(item.path("description"))).containsExactlyInAnyOrder("string", "null");

        // A $ref or an enum can't just gain a "null" type, so it becomes anyOf [T, null].
        assertThat(nullableVariant(schema("OwnerProfileResponse").path("properties").path("address"))
                .path("$ref").asString()).endsWith("/AddressDto");
        assertThat(nullableVariant(schema("InvoiceResponse").path("properties").path("paymentMethod"))
                .path("enum").isArray()).isTrue();

        // @JsonUnwrapped: the order's fields are inlined into the checkout response, with their nullability.
        JsonNode checkout = schema("CheckoutResponse");
        assertThat(strings(checkout.path("required"))).contains("id", "reference", "accessToken");
        assertThat(types(checkout.path("properties").path("notes"))).contains("null");
        assertThat(types(checkout.path("properties").path("accessToken"))).containsExactly("string");
    }

    /** Spring reads flat page/size/sort, so the spec must not offer a "pageable" object (sent as ?pageable[page]=1). */
    @Test
    void pagedOperationsTakeOptionalPageSizeAndSort() {
        Map<String, Integer> defaultSizes = Map.of(
                "GET /api/public/catalogue/items", 20,
                "GET /api/catalogue/items", 20,
                "GET /api/orders", 50,
                "GET /api/invoices", 50);
        defaultSizes.forEach((operation, defaultSize) -> {
            Map<String, JsonNode> parameters = new HashMap<>();
            operation(operation).path("parameters").forEach(p -> parameters.put(p.path("name").asString(), p));

            assertThat(parameters).as(operation).containsKeys("page", "size", "sort").doesNotContainKey("pageable");
            for (String name : List.of("page", "size", "sort")) {
                assertThat(parameters.get(name).path("in").asString()).as("%s %s", operation, name).isEqualTo("query");
                assertThat(parameters.get(name).path("required").asBoolean(false)).as("%s %s", operation, name).isFalse();
            }
            assertThat(parameters.get("size").path("schema").path("default").asInt()).as(operation).isEqualTo(defaultSize);
        });
    }

    @Test
    void slotsCarryTheirInstantsAndTheBakeryIsPublic() {
        JsonNode slot = schema("TimeSlotResponse").path("properties");
        for (String field : List.of("startsAt", "endsAt")) {
            assertThat(types(slot.path(field))).as(field).containsExactly("string");
            assertThat(slot.path(field).path("format").asString()).as(field).isEqualTo("date-time");
        }

        JsonNode bakery = operation("GET /api/public/bakery").path("responses").path("200")
                .path("content").path("application/json").path("schema");
        assertThat(bakery.path("$ref").asString()).endsWith("/PublicBakeryResponse");
        assertThat(strings(schema("PublicBakeryResponse").path("required")))
                .containsExactlyInAnyOrder("businessName", "timeZone", "logoUrl", "phone", "address");
        assertThat(types(schema("PublicBakeryResponse").path("properties").path("timeZone"))).containsExactly("string");
    }

    @Test
    void schemasAlsoSentAsRequestBodiesKeepOptionalFields() {
        // AddressDto is both read and written; every field may be left out of a profile update.
        assertThat(schema("AddressDto").has("required")).isFalse();
    }

    private static JsonNode nullableVariant(JsonNode property) {
        JsonNode anyOf = property.path("anyOf");
        assertThat(anyOf.size()).isEqualTo(2);
        assertThat(types(anyOf.get(1))).containsExactly("null");
        return anyOf.get(0);
    }

    private static JsonNode schema(String name) {
        JsonNode schema = spec.path("components").path("schemas").path(name);
        assertThat(schema.isMissingNode()).as("schema %s", name).isFalse();
        return schema;
    }

    private static JsonNode operation(String operation) {
        String[] parts = operation.split(" ", 2);
        JsonNode node = spec.path("paths").path(parts[1]).path(parts[0].toLowerCase());
        assertThat(node.isMissingNode()).as(operation).isFalse();
        return node;
    }

    private static void forEachOperation(BiConsumer<String, JsonNode> check) {
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                check.accept(operation.getKey().toUpperCase() + " " + path.getKey(), operation.getValue());
            }
        }
    }

    /** {@code type} as a list, whether the spec wrote it as one string or an array. */
    private static List<String> types(JsonNode schema) {
        JsonNode type = schema.path("type");
        return type.isArray() ? strings(type) : type.isMissingNode() ? List.of() : List.of(type.asString());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
