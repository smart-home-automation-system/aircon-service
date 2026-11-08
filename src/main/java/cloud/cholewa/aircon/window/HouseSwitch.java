package cloud.cholewa.aircon.window;

//The switch of the heating of the house (HAS-387), GET /home/heating of heating-service - only what this service reads
public record HouseSwitch(Boolean isHeatingEnabled) {
}
