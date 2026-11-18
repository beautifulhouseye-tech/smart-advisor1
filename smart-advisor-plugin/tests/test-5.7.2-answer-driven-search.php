<?php
$root = dirname( __DIR__ );
$search = file_get_contents( $root . '/services/class-smart-search.php' );
$profile = file_get_contents( $root . '/services/class-customer-intelligence.php' );
$planner = file_get_contents( $root . '/services/class-project-planner.php' );
$settings = file_get_contents( $root . '/core/class-project-type-settings.php' );
$controller = file_get_contents( $root . '/core/class-chat-controller.php' );
$question_config = json_decode( file_get_contents( $root . '/config/question-templates.json' ), true );
function sa572_has_legacy_category_map( $value ) { if ( ! is_array( $value ) ) return false; foreach ( $value as $key => $child ) { if ( in_array( (string) $key, [ 'map_to', 'fallback_categories' ], true ) ) return true; if ( sa572_has_legacy_category_map( $child ) ) return true; } return false; }
$checks = [
    'chat search loads saved customer preferences when consent exists' => strpos( $controller, 'profile_preferences_for_search' ) !== false && strpos( $controller, 'SA_Customer_Behavior::has_consent' ) !== false,
    'chat preserves array-based profile preferences for ranking' => strpos( $search, 'ARRAY_FILTER_USE_BOTH' ) !== false,
    'question config no longer contains active category maps' => is_array( $question_config ) && ! sa572_has_legacy_category_map( $question_config ),
    'chat merges saved profile hints into the search context' => strpos( $controller, '$search_known[ $pref_key ]' ) !== false && strpos( $controller, "'profile_hints'" ) !== false,
    'general advisor discovery is catalog-wide' => strpos( $search, 'fetch_catalog_product_ids' ) !== false,
    'bridal kit no longer bypasses answer-driven search with hardcoded category groups' => strpos( $controller, 'find_bridal_beauty_kit' ) === false && strpos( $search, 'find_bridal_beauty_kit' ) === false,
    'general advisor no longer resolves answer values to categories' => strpos( $search, 'resolve_category_term_ids' ) === false && strpos( $search, 'fallback_categories' ) === false,
    'general advisor discovery stays catalog-wide while alternatives use category-scoped discovery' => strpos( $search, 'public static function find(' ) !== false && strpos( $search, 'public static function find_alternatives_for_product(' ) !== false && strpos( $search, "'taxonomy' => 'product_cat'" ) !== false,
    'customer profile recommendations use catalog-wide candidates' => strpos( $profile, 'get_catalog_candidates( 40 )' ) !== false,
    'customer profile no longer filters candidates with category taxonomy' => strpos( $profile, "'taxonomy' => 'product_cat'" ) === false,
    'customer-entered disliked products are excluded' => strpos( $profile, '$disliked_hints' ) !== false && strpos( $profile, 'if ( $disliked ) continue;' ) !== false,
    'project plans use only selected category IDs' => strpos( $planner, 'get_products_by_category_term_ids( $term_ids' ) !== false,
    'project plan does not infer categories from legacy keywords' => strpos( $planner, 'foreach ( $need[\'keywords\']' ) === false,
    'project settings remove old group editor' => strpos( $settings, 'sa-add-existing-group' ) === false && strpos( $settings, 'مجموعات الاحتياجات القديمة' ) === false,
    'project category picker defaults to one product' => strpos( $settings, '?? 1' ) !== false,
];
$failed = 0;
foreach ( $checks as $label => $ok ) { echo ( $ok ? 'PASS ' : 'FAIL ' ) . $label . "\n"; if ( ! $ok ) $failed++; }
exit( $failed ? 1 : 0 );
