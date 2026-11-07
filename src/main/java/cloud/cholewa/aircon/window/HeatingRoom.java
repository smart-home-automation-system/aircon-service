package cloud.cholewa.aircon.window;

//A room of GET /home/heating/rooms of heating-service (HAS-384), only what this service reads of it: its name - the word
//of RoomName, kept as text so that a room this version of the SDK does not know fails nothing - and its window. Every
//other field of the answer is left unread
public record HeatingRoom(String name, RoomWindow window) {
}
