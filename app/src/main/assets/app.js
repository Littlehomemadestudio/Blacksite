function openView(view) {
  if (window.OracleNative && typeof window.OracleNative.open === "function") {
    window.OracleNative.open(view);
  }
}
function setActive(button) {
  document.querySelectorAll(".side-action").forEach(item => item.classList.remove("selected"));
  button.classList.add("selected");
}
function mobileActive(button) {
  document.querySelectorAll(".mobile-nav button").forEach(item => item.classList.remove("active"));
  button.classList.add("active");
}
const date = new Intl.DateTimeFormat(undefined, { weekday: "long", month: "long", day: "numeric" });
document.getElementById("today").textContent = date.format(new Date()).toUpperCase();
