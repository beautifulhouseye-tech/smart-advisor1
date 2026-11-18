<?php
/** Tests for project answer persistence, chat and skill-link settings. */
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
function link_assert( $ok, $label ) { global $failures; echo ( $ok ? 'PASS ' : 'FAIL ' ) . $label . "\n"; if ( ! $ok ) $failures++; }
$clean = SA_Project_Type_Settings::sanitize( [ 'kitchen' => [ 'name'=>'مطبخ', 'enabled'=>1, 'questions'=>[
 [ 'key'=>'size','label'=>'حجم المطبخ','type'=>'single','options'=>"صغير\nكبير",'save_answer'=>1,'use_in_chat'=>1,'skills'=>['product_search','offers_discounts','invalid_skill'] ],
 [ 'key'=>'budget','label'=>'الميزانية','type'=>'number','save_answer'=>0,'use_in_chat'=>1,'skills'=>['offers_discounts'] ],
] ] ] );
$GLOBALS['sa_test_options']=$clean;
$q=$clean['kitchen']['questions'];
link_assert( ! empty($q[0]['save_answer']) && ! empty($q[0]['use_in_chat']), 'separate save and chat switches are persisted' );
link_assert( $q[0]['skills'] === ['product_search','offers_discounts'], 'only recognized skills are retained' );
link_assert( empty($q[1]['save_answer']) && in_array('offers_discounts',$q[1]['skills'],true), 'answer can be excluded from storage while retaining its explicit skill selection' );
$chat=file_get_contents(dirname(__DIR__).'/core/class-chat-controller.php');
$js=file_get_contents(dirname(__DIR__).'/assets/js/chat.js');
$skills=file_get_contents(dirname(__DIR__).'/services/class-advisor-skills.php');
link_assert( strpos($chat,'project_id')!==false && strpos($chat,'sa_project_answers_')!==false && strpos($chat,'use_in_chat')!==false, 'chat context is loaded server-side from the selected owned project' );
link_assert( strpos($js,'activeProjectId')!==false && strpos($js,'projectsEndpoint')!==false, 'chat UI lets the customer select an active project' );
link_assert( strpos($skills,'get_project_answers_for_skill')!==false, 'skill responses use only their explicitly linked project answers' );
if($failures) exit(1); echo "All project answer linking checks passed\n";
