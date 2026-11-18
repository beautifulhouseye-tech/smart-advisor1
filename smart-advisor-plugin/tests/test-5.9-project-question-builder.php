<?php
/** Focused tests for project question configuration and custom project types. */
define( 'ABSPATH', '/tmp/' );
$GLOBALS['sa_test_options'] = [];
function get_option( $key, $default = [] ) { return $GLOBALS['sa_test_options']; }
function sanitize_key( $value ) { return preg_replace( '/[^a-z0-9_\-]/', '', strtolower( (string) $value ) ); }
function sanitize_text_field( $value ) { return trim( strip_tags( (string) $value ) ); }
function sanitize_textarea_field( $value ) { return trim( strip_tags( (string) $value ) ); }
function absint( $value ) { return abs( (int) $value ); }
function get_term( $id, $taxonomy ) { return (object) [ 'term_id' => (int) $id, 'name' => 'Category ' . (int) $id ]; }
function is_wp_error( $value ) { return false; }
require dirname( __DIR__ ) . '/core/class-project-type-settings.php';

$failures = 0;
function question_assert( $condition, $message ) {
    global $failures;
    echo ( $condition ? 'PASS ' : 'FAIL ' ) . $message . "\n";
    if ( ! $condition ) $failures++;
}

$clean = SA_Project_Type_Settings::sanitize( [
    'custom_kitchen' => [
        'name' => 'تجهيز المطبخ', 'enabled' => 1, 'sort' => 'rating_sales',
        'questions' => [
            [ 'key' => 'kitchen_size', 'label' => 'ما حجم المطبخ؟', 'type' => 'single', 'options' => "صغير\nمتوسط\nكبير", 'required' => 1 ],
            [ 'key' => 'needs', 'label' => 'ما الذي تحتاجه؟', 'type' => 'multiple', 'options' => "أواني\nأجهزة\nتخزين", 'required' => 0 ],
            [ 'key' => 'note', 'label' => 'ملاحظات إضافية', 'type' => 'text', 'options' => '', 'required' => 0 ],
            [ 'key' => 'bad', 'label' => 'اختيار بلا خيارات', 'type' => 'single', 'options' => '', 'required' => 0 ],
        ],
    ],
] );
$GLOBALS['sa_test_options'] = $clean;
$type = SA_Project_Type_Settings::get_type( 'custom_kitchen' );
question_assert( is_array( $type ) && count( $type['questions'] ) === 3, 'question sanitizer keeps supported questions and drops choice questions without options' );
question_assert( ( $type['questions'][0]['key'] ?? '' ) === 'kitchen_size' && ( $type['questions'][0]['required'] ?? 0 ) === 1, 'question key and required flag are preserved' );
question_assert( ( $type['questions'][1]['options'][2] ?? '' ) === 'تخزين', 'multiple-choice options are stored as a clean ordered list' );
question_assert( SA_Project_Type_Settings::get_type( 'custom_kitchen' )['name'] === 'تجهيز المطبخ', 'custom project type is retrievable by its own key' );
$frontend = file_get_contents( dirname( __DIR__ ) . '/core/class-frontend.php' );
$profile_js = file_get_contents( dirname( __DIR__ ) . '/assets/js/profile.js' );
$intelligence = file_get_contents( dirname( __DIR__ ) . '/services/class-customer-intelligence.php' );
$planner = file_get_contents( dirname( __DIR__ ) . '/services/class-project-planner.php' );
question_assert( strpos( $frontend, 'projectTypes' ) !== false && strpos( $frontend, 'data-project-questions' ) !== false, 'profile form receives the enabled project types and a dynamic question container' );
question_assert( strpos( $profile_js, 'renderProjectQuestions' ) !== false && strpos( $profile_js, 'collectProjectAnswers' ) !== false, 'profile JavaScript renders type-specific questions and collects answers' );
question_assert( strpos( $intelligence, 'sanitize_project_answers' ) !== false && strpos( $intelligence, 'sa_project_answers_' ) !== false, 'REST API validates and saves answers under the individual project owner' );
question_assert( strpos( $planner, 'product_answer_score' ) !== false && strpos( $planner, 'SA_Project_Type_Settings::get_type( (string) $project_type )' ) !== false, 'custom project types resolve and answers influence project product ranking' );

if ( $failures ) exit( 1 );
echo "All project question builder checks passed\n";
