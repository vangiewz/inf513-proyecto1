<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;
use Illuminate\Support\Facades\DB;

return new class extends Migration
{
    public function up(): void
    {
        // Cache de la oferta externa para clientes JDBC; no es un segundo CRM.
        Schema::create('catalogo_cache', function (Blueprint $t) {
            $t->id();
            $t->string('codigo')->unique();
            $t->string('external_anuncio_id')->nullable();
            $t->string('programa_codigo');
            $t->string('programa_nombre');
            $t->string('nombre');
            $t->text('descripcion')->nullable();
            $t->decimal('precio', 12, 2);
            $t->string('estado');
            $t->timestamp('sincronizado_en');
        });
        Schema::create('motivos_perdida', function (Blueprint $t) {
            $t->id();
            $t->string('nombre', 80)->unique();
            $t->string('categoria', 20);
        });
        Schema::table('prospectos', function (Blueprint $t) {
            $t->string('origen', 20)->default('Web');
        });
        Schema::table('intereses', function (Blueprint $t) {
            $t->foreignId('motivo_perdida_id')->nullable()->constrained('motivos_perdida')->restrictOnDelete();
        });
        Schema::table('recordatorios', function (Blueprint $t) {
            $t->boolean('notificado')->default(false);
        });
        Schema::create('movimientos_interes', function (Blueprint $t) {
            $t->id();
            $t->foreignId('interes_id')->constrained('intereses')->restrictOnDelete();
            $t->foreignId('usuario_id')->nullable()->constrained('usuarios')->restrictOnDelete();
            $t->string('estado_anterior')->nullable();
            $t->string('estado_nuevo');
            $t->text('detalle')->nullable();
            $t->timestamp('fecha')->useCurrent();
            $t->index(['interes_id', 'fecha']);
        });
        Schema::create('log_comandos', function (Blueprint $t) {
            $t->id();
            $t->foreignId('usuario_id')->nullable()->constrained('usuarios')->restrictOnDelete();
            $t->string('remitente', 160);
            $t->string('asunto', 300);
            $t->string('resultado', 20);
            $t->text('detalle')->nullable();
            $t->timestamp('fecha')->useCurrent();
        });
        DB::table('motivos_perdida')->insert(['nombre' => 'Sin clasificar (web)', 'categoria' => 'Otro']);
        DB::statement('CREATE UNIQUE INDEX usuarios_correo_lower_unique ON usuarios (lower(correo))');
        DB::statement('CREATE UNIQUE INDEX prospectos_correo_lower_unique ON prospectos (lower(correo))');
        DB::statement("ALTER TABLE motivos_perdida ADD CONSTRAINT motivos_categoria_check CHECK (categoria IN ('Económico','Horario','Incompatibilidad','Contacto','Otro'))");
        // El historial captura tambien los cambios hechos desde Laravel. JDBC escribe
        // su detalle y actor explicitamente dentro de su transaccion.
        DB::unprepared(<<<'SQL'
            CREATE FUNCTION registrar_movimiento_web() RETURNS trigger LANGUAGE plpgsql AS $$
            DECLARE anterior text; vendedor_anterior bigint;
            BEGIN
                IF current_setting('crm.canal',true) IS DISTINCT FROM 'correo' THEN
                    IF TG_OP='UPDATE' THEN
                        anterior := OLD.estado;
                        vendedor_anterior := OLD.vendedor_id;
                    END IF;
                    IF TG_OP='INSERT' OR NEW.estado IS DISTINCT FROM anterior OR NEW.vendedor_id IS DISTINCT FROM vendedor_anterior THEN
                        INSERT INTO movimientos_interes(interes_id,usuario_id,estado_anterior,estado_nuevo,detalle)
                        VALUES(NEW.id, NULLIF(current_setting('crm.usuario_id',true),'')::bigint,anterior,NEW.estado,
                            'Cambio desde web. Vendedor: ' || COALESCE(vendedor_anterior::text,'sin asignar') || ' -> ' || COALESCE(NEW.vendedor_id::text,'sin asignar'));
                    END IF;
                END IF;
                RETURN NEW;
            END $$;
            SQL);
        // AFTER permite la FK del movimiento al interes recien creado.
        DB::unprepared(<<<'SQL'
            CREATE FUNCTION clasificar_perdida() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.estado <> 'Perdido' THEN NEW.motivo_perdida_id := NULL;
                ELSIF NEW.motivo_perdida_id IS NULL THEN
                    SELECT id INTO NEW.motivo_perdida_id FROM motivos_perdida WHERE nombre='Sin clasificar (web)';
                END IF;
                RETURN NEW;
            END $$;
            CREATE TRIGGER intereses_clasificar BEFORE INSERT OR UPDATE ON intereses
                FOR EACH ROW EXECUTE FUNCTION clasificar_perdida();
            CREATE TRIGGER intereses_historial AFTER INSERT OR UPDATE ON intereses
                FOR EACH ROW EXECUTE FUNCTION registrar_movimiento_web();
            SQL);
    }

    public function down(): void
    {
        DB::unprepared('DROP TRIGGER IF EXISTS intereses_historial ON intereses; DROP TRIGGER IF EXISTS intereses_clasificar ON intereses; DROP FUNCTION IF EXISTS registrar_movimiento_web(); DROP FUNCTION IF EXISTS clasificar_perdida();');
        DB::statement('DROP INDEX IF EXISTS usuarios_correo_lower_unique');
        DB::statement('DROP INDEX IF EXISTS prospectos_correo_lower_unique');
        Schema::dropIfExists('log_comandos');
        Schema::dropIfExists('movimientos_interes');
        Schema::table('recordatorios', fn (Blueprint $t) => $t->dropColumn('notificado'));
        Schema::table('intereses', fn (Blueprint $t) => $t->dropConstrainedForeignId('motivo_perdida_id'));
        Schema::table('prospectos', fn (Blueprint $t) => $t->dropColumn('origen'));
        Schema::dropIfExists('motivos_perdida');
        Schema::dropIfExists('catalogo_cache');
    }
};
