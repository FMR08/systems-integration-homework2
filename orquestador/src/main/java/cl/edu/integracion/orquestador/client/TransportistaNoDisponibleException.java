package cl.edu.integracion.orquestador.client;

public class TransportistaNoDisponibleException extends RuntimeException {
    public TransportistaNoDisponibleException(String msg) {
        super(msg);
    }
}