<?php
define( 'ABSPATH', '/tmp/' );
define( 'MBA_SANDBOX_PATH', dirname( __DIR__ ) . '/' );

$failures = 0;
function assert_true( $cond, $msg ) {
    global $failures;
    if ( $cond ) {
        echo "PASS  {$msg}\n";
        return;
    }
    $failures++;
    echo "FAIL  {$msg}\n";
}

require MBA_SANDBOX_PATH . 'services/class-project-planner.php';

assert_true( method_exists( 'SA_Project_Planner', 'needs_beauty_options' ), 'planner exposes needs_beauty_options' );
assert_true( method_exists( 'SA_Project_Planner', 'beauty_option_fields' ), 'planner exposes beauty_option_fields' );

if ( method_exists( 'SA_Project_Planner', 'needs_beauty_options' ) ) {
    assert_true( SA_Project_Planner::needs_beauty_options( 'wedding', 'مستلزمات مستحضرات التجميل العروسة' ) === true, 'bridal cosmetics project needs beauty options'
    );
    assert_true( SA_Project_Planner::needs_beauty_options( 'wedding', 'تجهيز حفل زفاف' ) === false, 'generic wedding plan does not force beauty options' );
    assert_true( SA_Project_Planner::needs_beauty_options( 'construction' ) === false, 'construction plan does not need beauty options' );
    assert_true( SA_Project_Planner::needs_beauty_options( 'home_furnishing' ) === false, 'home plan does not need beauty options' );
}

$fields = method_exists( 'SA_Project_Planner', 'beauty_option_fields' ) ? SA_Project_Planner::beauty_option_fields() : [];
$keys = [];
foreach ( (array) $fields as $field ) {
    if ( ! empty( $field['key'] ) ) $keys[] = $field['key'];
}
assert_true( in_array( 'skin_type', $keys, true ), 'beauty fields include skin_type' );
assert_true( in_array( 'skin_tone', $keys, true ), 'beauty fields include skin_tone' );
assert_true( in_array( 'hair_type', $keys, true ), 'beauty fields include hair_type' );

$js = file_get_contents( MBA_SANDBOX_PATH . 'assets/js/profile.js' );
assert_true( strpos( $js, 'beauty_options' ) !== false, 'profile.js reads beauty_options from the plan API' );
assert_true( strpos( $js, 'skin_tone' ) !== false && strpos( $js, 'hair_type' ) !== false, 'profile.js collects skin_tone and hair_type before loading a wedding plan' );

$planner = file_get_contents( MBA_SANDBOX_PATH . 'services/class-project-planner.php' );
assert_true( strpos( $planner, 'skin_tone' ) !== false && strpos( $planner, 'hair_type' ) !== false, 'planner accepts beauty filters from the request' );

$tpl = json_decode( file_get_contents( MBA_SANDBOX_PATH . 'config/question-templates.json' ), true );
$beauty = $tpl['types']['العناية_بالبشرة'] ?? [];
$qkeys = [];
foreach ( $beauty['critical_questions'] ?? [] as $q ) {
    if ( ! empty( $q['key'] ) ) $qkeys[] = $q['key'];
}
assert_true( in_array( 'skin_tone', $qkeys, true ), 'cosmetics template has skin_tone' );
assert_true( in_array( 'hair_type', $qkeys, true ), 'cosmetics template has hair_type' );
assert_true( in_array( 'need', $qkeys, true ), 'cosmetics template has need (بشرة/شعر/مكياج)' );

if ( $failures > 0 ) {
    echo "\n{$failures} failing assertion(s)\n";
    exit( 1 );
}
echo "\nAll assertions passed\n";
exit( 0 );
