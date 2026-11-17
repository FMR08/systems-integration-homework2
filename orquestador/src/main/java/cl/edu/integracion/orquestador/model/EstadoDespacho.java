package cl.edu.integracion.orquestador.model;

public enum EstadoDespacho {
    INICIADA,
    INVENTARIO_RESERVADO,
    TRANSPORTE_AGENDADO,
    TRACKING_GENERADO,
    COMPLETADA,
    COMPENSANDO,
    COMPENSADA,
    FALLIDA
}