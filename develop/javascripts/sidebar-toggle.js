document.addEventListener("DOMContentLoaded", function () {
  var btn = document.getElementById("sidebar-toggle");
  if (!btn) return;

  var STORAGE_KEY = "aosc-sidebar-collapsed";
  var collapsed = localStorage.getItem(STORAGE_KEY) === "true";

  if (collapsed) document.body.classList.add("sidebar-collapsed");

  btn.addEventListener("click", function () {
    collapsed = !collapsed;
    document.body.classList.toggle("sidebar-collapsed", collapsed);
    localStorage.setItem(STORAGE_KEY, collapsed);
  });
});
