package cl.edu.integracion.orquestador.client;

public class TransportistaNoDisponibleException extends RuntimeException {
    private final boolean noEnviada;

    /** noEnviada = true cuando la petición nunca salió (circuito abierto o bulkhead lleno). */
    public TransportistaNoDisponibleException(String msg, boolean noEnviada) {
        super(msg);
        this.noEnviada = noEnviada;
    }

    public boolean isNoEnviada() {
        return noEnviada;
    }
}