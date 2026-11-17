<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // La oferta pertenece al SIS; la responsabilidad comercial es local al CRM.
        Schema::create('programas_responsables', function (Blueprint $t) {
            $t->string('external_programa_id')->primary();
            $t->foreignId('coordinador_id')->constrained('usuarios')->restrictOnDelete();
            $t->timestamps();
        });
        Schema::table('log_comandos', fn (Blueprint $t) => $t->string('rol_registrado', 40)->nullable());
        // Referencias del mismo modelo web; no sustituye descuentos configurados por el grupo.
        foreach ([['3_MAS_MODULOS', '3 o mas modulos', 10], ['PROGRAMA_COMPLETO', 'Programa completo', 15], ['CLIENTE_RECURRENTE', 'Cliente recurrente', 10]] as [$codigo, $nombre, $porcentaje]) {
            DB::table('descuentos')->insertOrIgnore(compact('codigo', 'nombre', 'porcentaje') + ['estado' => 'activo', 'created_at' => now(), 'updated_at' => now()]);
        }
    }

    public function down(): void
    {
        Schema::table('log_comandos', fn (Blueprint $t) => $t->dropColumn('rol_registrado'));
        Schema::dropIfExists('programas_responsables');
    }
};
