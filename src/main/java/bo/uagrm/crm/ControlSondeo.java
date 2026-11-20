package bo.uagrm.crm;

/** Política de demora desde el FIN del sondeo: nunca se recupera tiempo perdido con ráfagas. */
public final class ControlSondeo {
  private final long normal;
  private final long maximo;
  private final long permanente;

  public ControlSondeo(long normal, long maximo, long permanente) {
    if (normal <= 0 || maximo < normal || permanente < maximo)
      throw new IllegalArgumentException("Intervalos de sondeo inválidos");
    this.normal = normal;
    this.maximo = maximo;
    this.permanente = permanente;
  }

  public long demora(int fallasConsecutivas, boolean falloPermanente) {
    if (falloPermanente) return permanente;
    long demora = normal;
    for (int i = 1; i < fallasConsecutivas && demora < maximo; i++)
      demora = demora > maximo / 2 ? maximo : demora * 2;
    return Math.min(demora, maximo);
  }
}
