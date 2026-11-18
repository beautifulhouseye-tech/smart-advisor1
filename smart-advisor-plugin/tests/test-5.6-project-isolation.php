<?php
define( 'ABSPATH', '/tmp/' );
require dirname( __DIR__ ) . '/services/class-project-planner.php';
$failures = 0;
function sa56_assert( $condition, $message ) {
    global $failures;
    echo ( $condition ? "PASS  " : "FAIL  " ) . $message . "\n";
    if ( ! $condition ) $failures++;
}

$name = 'مستلزمات مستحضرات التجميل العروسة';
sa56_assert( SA_Project_Planner::resolve_template_key( 'wedding', $name ) === 'bridal_beauty', 'project name overrides broad wedding type with bridal beauty scope' );
sa56_assert( SA_Project_Planner::needs_beauty_options( 'wedding', $name ), 'bridal beauty project requests relevant beauty options' );
sa56_assert( ! SA_Project_Planner::needs_beauty_options( 'wedding', 'تجهيز حفل زفاف' ), 'generic wedding project does not force beauty questions' );
sa56_assert( SA_Project_Planner::resolve_template_key( 'home_furnishing', 'تجهيز المطبخ' ) === 'home_furnishing', 'kitchen/home project keeps its own independent scope' );
sa56_assert( SA_Project_Planner::resolve_template_key( 'wedding', 'مستلزمات المطبخ' ) === 'home_furnishing', 'specific project name takes priority over a broad project type' );

$source = file_get_contents( dirname( __DIR__ ) . '/services/class-project-planner.php' );
sa56_assert( strpos( $source, "'key'=>'skin_body'" ) === false && strpos( $source, "'key'=>'living'" ) === false, 'legacy hardcoded product groups are removed from planner templates' );
sa56_assert( strpos( $source, 'SA_Project_Type_Settings::apply_template' ) !== false && strpos( $source, 'get_products_by_category_term_ids( $term_ids' ) !== false, 'project product groups come only from selected WooCommerce categories' );
sa56_assert( strpos( $source, "'budget_share'=>(" ) !== false, 'budget percentages are omitted when no budget is set' );
sa56_assert( strpos( $source, '$seen_product_ids' ) !== false, 'duplicate products are suppressed across groups' );
$js = file_get_contents( dirname( __DIR__ ) . '/assets/js/profile.js' );
sa56_assert( strpos( $js, "group.budget_share!==null" ) !== false, 'frontend hides budget percentages when no budget exists' );

sa56_assert( strpos( $source, 'manage_project_outputs' ) !== false, 'project output actions have a dedicated REST handler' );
sa56_assert( strpos( $source, 'sa_project_removed_products_' ) !== false, 'removed products are persisted per customer project' );
sa56_assert( strpos( $source, 'category_name' ) !== false && strpos( $source, 'category_url' ) !== false, 'project product cards expose WooCommerce category name and URL' );
sa56_assert( strpos( $source, 'add_to_cart( $product_id, 1 )' ) !== false, 'project output endpoint adds eligible products to WooCommerce cart' );
sa56_assert( strpos( $js, 'إضافة الكل إلى السلة' ) !== false && strpos( $js, 'أضف للسلة' ) !== false, 'frontend renders individual and bulk add-to-cart controls' );
sa56_assert( strpos( $js, "action:'remove'" ) !== false, 'frontend can remove a product from this project output list' );
sa56_assert( strpos( $source, '/outputs' ) !== false, 'project outputs REST route is registered for bulk and individual cart actions' );
sa56_assert( strpos( $source, "'methods' => 'POST'" ) !== false, 'project output actions accept POST requests' );
sa56_assert( strpos( $source, 'wc_load_cart()' ) !== false, 'WooCommerce cart/session are initialized in REST requests' );
sa56_assert( strpos( $source, 'sa_project_saved_plan_' ) !== false && strpos( $source, 'update_user_meta( get_current_user_id(), $plan_meta_key, $plan )' ) !== false, 'generated project plan is persisted per customer and project' );
sa56_assert( strpos( $source, 'get_user_meta( get_current_user_id(), $plan_meta_key, true )' ) !== false, 'saved project plan is restored on revisit instead of regenerated' );
sa56_assert( strpos( $source, 'Keep the persisted plan in sync' ) !== false, 'removed project output products stay removed after refresh' );

$progress = file_get_contents( dirname( __DIR__ ) . '/services/class-project-progress.php' );
sa56_assert( strpos( $progress, 'WHERE project_id=%d AND user_id=%d' ) !== false, 'progress records remain isolated by project and owner' );
sa56_assert( strpos( $progress, 'sync_keys' ) !== false, 'project progress synchronizes current plan groups without cross-project deletion' );

if ( $failures ) { echo "\n{$failures} failure(s)\n"; exit( 1 ); }
echo "\nAll Smart Advisor 5.6 project isolation checks passed\n";
