<?php
$root = dirname( __DIR__ );
$settings = file_get_contents( $root . '/core/class-project-type-settings.php' );
$planner = file_get_contents( $root . '/services/class-project-planner.php' );
$checks = [
    'real WooCommerce categories remain available in project settings' => strpos( $settings, "'taxonomy'=>'product_cat'" ) !== false,
    'category picker keeps the WooCommerce hierarchy' => strpos( $settings, '$children[ (int) $term->parent ]' ) !== false,
    'each selected category stores its own limit' => strpos( $settings, "'category_rules'" ) !== false,
    'default category limit is one' => strpos( $settings, "?? 1" ) !== false && strpos( $settings, '$limit = $selected ? (int) $rules[ $id ][\'limit\'] : 1' ) !== false,
    'only explicitly selected category IDs become project groups' => strpos( $settings, '\'category_ids\'=>[ (int) $term_id ]' ) !== false,
    'planner queries only explicit category IDs' => strpos( $planner, 'get_products_by_category_term_ids( $term_ids' ) !== false,
    'planner has no keyword-to-category inference loop' => strpos( $planner, 'foreach ( $need[\'keywords\']' ) === false,
    'empty category selection produces an empty sentinel group' => strpos( $settings, 'selected_categories_empty' ) !== false,
    'legacy groups are removed from normalized settings' => strpos( $settings, 'unset( $type_config[\'groups\'] )' ) !== false,
    'project settings UI no longer renders legacy group controls' => strpos( $settings, 'مجموعات الاحتياجات القديمة' ) === false && strpos( $settings, 'sa-add-existing-group' ) === false,
];
$failed = 0;
foreach ( $checks as $label => $ok ) { echo ( $ok ? 'PASS ' : 'FAIL ' ) . $label . "\n"; if ( ! $ok ) $failed++; }
exit( $failed ? 1 : 0 );
